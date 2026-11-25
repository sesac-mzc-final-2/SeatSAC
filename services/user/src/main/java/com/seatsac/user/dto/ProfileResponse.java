package com.seatsac.user.dto;

import com.seatsac.common.core.time.Times;
import com.seatsac.user.entity.User;
import java.time.OffsetDateTime;

/**
 * GET·PATCH /users/me 응답 — 계약 {@code Profile}. role·grade는 저장하지 않고 요청의 X-User-Role·X-User-Grade 그대로
 * (grade는 {@link com.seatsac.user.common.UserGrade#fromHeader}로 NORMAL·VIP 두 값만)
 */
public record ProfileResponse(String userId, String email, String name, String role, String grade,
                              OffsetDateTime createdAt) {

    public static ProfileResponse of(User user, String role, String grade) {
        String name = user.getName() == null || user.getName().isEmpty() ? null : user.getName();
        return new ProfileResponse(user.getUserId(), user.getEmail(), name, role, grade,
            Times.toApi(user.getCreatedAt()));
    }
}
