package com.seatsac.user.dto;

import com.seatsac.common.core.time.Times;
import com.seatsac.user.entity.User;
import java.time.OffsetDateTime;

/** GET·PATCH /users/me 응답 — 계약 {@code Profile}. role은 저장하지 않고 요청의 X-User-Role 그대로 */
public record ProfileResponse(String userId, String email, String name, String role, OffsetDateTime createdAt) {

    public static ProfileResponse of(User user, String role) {
        String name = user.getName() == null || user.getName().isEmpty() ? null : user.getName();
        return new ProfileResponse(user.getUserId(), user.getEmail(), name, role, Times.toApi(user.getCreatedAt()));
    }
}
