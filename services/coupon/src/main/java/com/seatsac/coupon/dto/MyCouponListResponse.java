package com.seatsac.coupon.dto;

import java.util.List;

/** 계약 {@code MyCouponList} — 페이징 없는 목록은 {@code {items}} */
public record MyCouponListResponse(List<CouponIssueResponse> items) {
}
