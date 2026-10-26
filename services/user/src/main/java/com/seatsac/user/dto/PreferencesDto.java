package com.seatsac.user.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * USR-05 선호 정보 — 계약 {@code Preferences}. 요청(PUT 전체 교체)과 응답이 같은 모양이다.
 * 순위·극장 중복과 극장 존재 여부는 형식이 아니라 값의 문제라 {@code PreferenceService}가 검사한다.
 */
public record PreferencesDto(
    @NotNull @Size(max = 3) List<@Valid PreferredTheater> preferredTheaters,
    TimeSlot preferredTimeSlot,
    @NotNull List<@NotBlank String> preferredGenres
) {
    public PreferencesDto {
        preferredTheaters = preferredTheaters == null ? null : List.copyOf(preferredTheaters);
        preferredGenres = preferredGenres == null ? null : List.copyOf(preferredGenres);
    }

    public static PreferencesDto empty() {
        return new PreferencesDto(List.of(), null, List.of());
    }

    public record PreferredTheater(
        @NotNull @Min(1) @Max(3) Integer rank,
        @NotNull @Positive Long theaterId
    ) {
    }
}
