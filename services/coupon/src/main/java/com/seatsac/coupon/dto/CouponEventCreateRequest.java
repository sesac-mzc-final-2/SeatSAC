package com.seatsac.coupon.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** 계약 {@code CouponEventCreateRequest}(internal). 범위는 계약 그대로 */
public record CouponEventCreateRequest(
    @NotBlank @Size(max = 100) String title,
    @NotNull @Min(1) @Max(99) Integer discountRate,
    @NotNull OffsetDateTime startsAt,
    @Min(1) @Max(1440) Integer durationMinutes,
    @NotNull @Min(1) @Max(10000) Integer totalQuantity,
    @Pattern(regexp = "VIP") String eligibleGrade) {

    public int durationOrDefault() {
        return durationMinutes == null ? 60 : durationMinutes;
    }

    public String gradeOrDefault() {
        return eligibleGrade == null ? "VIP" : eligibleGrade;
    }
}
