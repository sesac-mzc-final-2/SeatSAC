package com.seatsac.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PATCH /users/me 본문 — name 1~20자(계약). 공백만인 이름은 받지 않는다 */
public record UpdateProfileRequest(@NotBlank @Size(max = 20) String name) {
}
