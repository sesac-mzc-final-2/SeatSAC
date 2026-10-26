package com.seatsac.user.service;

import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.user.client.CatalogClient;
import com.seatsac.user.common.UserErrorCode;
import com.seatsac.user.dto.PreferencesDto;
import com.seatsac.user.dto.PreferencesDto.PreferredTheater;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * USR-05 선호 정보 — 계약 {@code GET·PUT /users/me/preferences}.
 * <ul>
 *   <li>조회: 없으면 빈 값 200 ({@code preferredTheaters: [], preferredTimeSlot: null, preferredGenres: []})</li>
 *   <li>저장(전체 교체): 극장 최대 3곳(형식 검증), rank·극장 중복 없음, 없는 theaterId는 400 —
 *       catalog {@code GET /internal/theaters}로 확인하고 catalog가 답하지 않으면 검증 없이 저장(계약)</li>
 *   <li>프로필 행이 없으면 먼저 만든다(FK) — {@link ProfileService#ensureProfile}</li>
 * </ul>
 */
@Service
public class PreferenceService {

    private static final Logger log = LoggerFactory.getLogger(PreferenceService.class);

    private final PreferenceStore store;
    private final ProfileService profileService;
    private final CatalogClient catalogClient;
    private final Clock clock;

    public PreferenceService(PreferenceStore store, ProfileService profileService, CatalogClient catalogClient,
                             Clock clock) {
        this.store = store;
        this.profileService = profileService;
        this.catalogClient = catalogClient;
        this.clock = clock;
    }

    public PreferencesDto getMyPreferences(String userId) {
        return store.find(userId);
    }

    public PreferencesDto replaceMyPreferences(String userId, String authorization, PreferencesDto request) {
        validate(request.preferredTheaters());
        profileService.ensureProfile(userId, authorization);
        return store.replace(userId, request, clock.instant());
    }

    private void validate(List<PreferredTheater> theaters) {
        List<ErrorDetail> details = new ArrayList<>();
        Set<Integer> ranks = new HashSet<>();
        Set<Long> ids = new LinkedHashSet<>();
        for (int i = 0; i < theaters.size(); i++) {
            PreferredTheater t = theaters.get(i);
            if (!ranks.add(t.rank())) {
                details.add(ErrorDetail.of(field(i, "rank"), t.rank(), UserErrorCode.DUPLICATE_RANK.name()));
            }
            if (!ids.add(t.theaterId())) {
                details.add(ErrorDetail.of(field(i, "theaterId"), t.theaterId(), UserErrorCode.DUPLICATE_THEATER.name()));
            }
        }
        if (!details.isEmpty()) {
            throw UserErrorCode.DUPLICATE_RANK.exception(details);
        }

        Optional<Set<Long>> existing = catalogClient.existingTheaterIds(ids);
        if (existing.isEmpty()) {
            log.warn("catalog theaters unavailable - preferences saved without theater check ids={}", ids);
            return;
        }
        for (int i = 0; i < theaters.size(); i++) {
            Long theaterId = theaters.get(i).theaterId();
            if (!existing.get().contains(theaterId)) {
                details.add(ErrorDetail.of(field(i, "theaterId"), theaterId, UserErrorCode.THEATER_NOT_FOUND.name()));
            }
        }
        if (!details.isEmpty()) {
            throw UserErrorCode.THEATER_NOT_FOUND.exception(details);
        }
    }

    private static String field(int index, String name) {
        return "preferredTheaters[" + index + "]." + name;
    }
}
