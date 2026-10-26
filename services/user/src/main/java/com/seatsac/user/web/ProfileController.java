package com.seatsac.user.web;

import com.seatsac.common.web.user.AuthUser;
import com.seatsac.common.web.user.CurrentUser;
import com.seatsac.user.dto.ProfileResponse;
import com.seatsac.user.dto.UpdateProfileRequest;
import com.seatsac.user.service.ProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로필 — contracts/openapi/user.openapi.yaml {@code /users/me}. 메서드 이름 = operationId.
 * {@code Authorization}은 Gateway가 검증한 뒤 그대로 넘겨 준 헤더로, 프로필을 처음 만들 때만 쓴다(Cognito GetUser).
 */
@RestController
@RequestMapping("/users/me")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ProfileResponse getMyProfile(
        @CurrentUser AuthUser user,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return profileService.getMyProfile(user, authorization);
    }

    @PatchMapping
    public ProfileResponse updateMyProfile(
        @CurrentUser AuthUser user,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @Valid @RequestBody UpdateProfileRequest request) {
        return profileService.updateMyProfile(user, authorization, request.name());
    }
}
