package com.seatsac.coupon.controller;

import com.seatsac.common.core.http.Headers;
import com.seatsac.common.web.user.CurrentUser;
import com.seatsac.coupon.common.CouponHeaders;
import com.seatsac.coupon.dto.CouponIssueResponse;
import com.seatsac.coupon.dto.CurrentCouponEventResponse;
import com.seatsac.coupon.dto.MyCouponListResponse;
import com.seatsac.coupon.service.CouponClaimService;
import com.seatsac.coupon.service.CouponQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 API(coupon.openapi.yaml, CPN-01~03). Gateway 라우트 {@code /api/coupons/**} → StripPrefix → {@code /coupons/**}.
 * 사용자는 Gateway가 넣는 {@code X-User-Id}, 등급은 {@code X-User-Grade}(토큰 {@code cognito:groups})를 그대로 믿는다.
 */
@RestController
@RequiredArgsConstructor
public class CouponController {

    private final CouponQueryService queries;
    private final CouponClaimService claims;

    /** CPN-01 지금 보여 줄 이벤트 */
    @GetMapping("/coupons/events/current")
    public CurrentCouponEventResponse current(@CurrentUser String userId) {
        return queries.current(userId);
    }

    /** CPN-02 선착순 발급 — 본문 없음, Idempotency-Key 필수 */
    @PostMapping("/coupons/events/{eventId}/claim")
    @ResponseStatus(HttpStatus.CREATED)
    public CouponIssueResponse claim(@CurrentUser String userId, @PathVariable long eventId,
                                     @RequestHeader(value = CouponHeaders.USER_GRADE, required = false) String grade,
                                     @RequestHeader(value = Headers.IDEMPOTENCY_KEY, required = false) String key) {
        return claims.claim(userId, grade, eventId, key);
    }

    /** CPN-03 내 쿠폰 */
    @GetMapping("/coupons/me")
    public MyCouponListResponse mine(@CurrentUser String userId) {
        return queries.mine(userId);
    }
}
