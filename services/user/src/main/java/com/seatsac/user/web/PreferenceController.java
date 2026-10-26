package com.seatsac.user.web;

import com.seatsac.common.web.user.CurrentUser;
import com.seatsac.user.dto.PreferencesDto;
import com.seatsac.user.service.PreferenceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** USR-05 — contracts/openapi/user.openapi.yaml {@code /users/me/preferences}. 메서드 이름 = operationId */
@RestController
@RequestMapping("/users/me/preferences")
public class PreferenceController {

    private final PreferenceService preferenceService;

    public PreferenceController(PreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    public PreferencesDto getMyPreferences(@CurrentUser String userId) {
        return preferenceService.getMyPreferences(userId);
    }

    /** 전체 교체. 프로필 행이 없으면 먼저 만들므로(FK) Authorization이 그때 필요하다 */
    @PutMapping
    public PreferencesDto replaceMyPreferences(
        @CurrentUser String userId,
        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
        @Valid @RequestBody PreferencesDto request) {
        return preferenceService.replaceMyPreferences(userId, authorization, request);
    }
}
