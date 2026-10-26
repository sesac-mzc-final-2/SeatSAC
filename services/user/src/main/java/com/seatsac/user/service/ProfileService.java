package com.seatsac.user.service;

import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.web.user.AuthUser;
import com.seatsac.user.client.CognitoUserInfoClient;
import com.seatsac.user.client.CognitoUserInfoClient.CognitoUser;
import com.seatsac.user.common.UserErrorCode;
import com.seatsac.user.dto.ProfileResponse;
import com.seatsac.user.entity.User;
import com.seatsac.user.repository.UserRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 프로필 — 계약 {@code GET·PATCH /users/me}. 행이 없으면 요청의 Access Token으로 Cognito {@code GetUser}를 불러 만든다
 * (ADR-37·54). 행이 있으면 Cognito를 부르지 않는다. 선호 저장(USR-05)도 FK 때문에 같은 경로로 먼저 행을 만든다.
 *
 * <ul>
 *   <li>행 없음 + 토큰 없음 → 401 {@code TOKEN_REQUIRED}</li>
 *   <li>Cognito가 준 {@code sub}가 {@code X-User-Id}와 다르면 401 {@code IDENTITY_MISMATCH}(다른 사람 토큰)</li>
 *   <li>Cognito 실패 → 503 {@code IDENTITY_UNAVAILABLE} (행을 만들지 않는다)</li>
 *   <li>첫 호출이 동시에 들어와 INSERT가 유일 키에 걸리면 다른 쪽이 만든 행을 읽는다</li>
 * </ul>
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserRepository userRepository;
    private final ProfileStore store;
    private final CognitoUserInfoClient cognito;
    private final Clock clock;

    public ProfileService(UserRepository userRepository, ProfileStore store, CognitoUserInfoClient cognito,
                          Clock clock) {
        this.userRepository = userRepository;
        this.store = store;
        this.cognito = cognito;
        this.clock = clock;
    }

    public ProfileResponse getMyProfile(AuthUser user, String authorization) {
        return ProfileResponse.of(ensureProfile(user.userId(), authorization), user.role());
    }

    public ProfileResponse updateMyProfile(AuthUser user, String authorization, String name) {
        ensureProfile(user.userId(), authorization);
        User renamed = store.rename(user.userId(), name.strip(), clock.instant());
        return ProfileResponse.of(renamed, user.role());
    }

    /** 행이 있으면 그대로, 없으면 Cognito 값으로 만들어 돌려준다 */
    public User ensureProfile(String userId, String authorization) {
        return userRepository.findById(userId).orElseGet(() -> create(userId, authorization));
    }

    private User create(String userId, String authorization) {
        CognitoUser identity = cognito.getUser(AccessTokens.bearer(authorization));
        if (!userId.equalsIgnoreCase(identity.sub())) {
            log.warn("cognito sub differs from X-User-Id userId={}", userId);
            throw UserErrorCode.IDENTITY_MISMATCH.exception(
                ErrorDetail.of("Authorization", null, UserErrorCode.IDENTITY_MISMATCH.name()));
        }
        try {
            User created = store.create(new User(userId, identity.email(), identity.name(), clock.instant()));
            log.info("profile created userId={}", userId);
            return created;
        } catch (DataIntegrityViolationException e) {
            // 같은 사용자의 첫 호출이 동시에 들어온 경우 — 먼저 만든 행을 쓴다. 그 밖(다른 sub가 같은 email)이면 그대로 500
            return userRepository.findById(userId).orElseThrow(() -> e);
        }
    }
}
