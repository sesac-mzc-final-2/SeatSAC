package com.seatsac.coupon.controller;

import com.seatsac.coupon.dto.CouponEventCreateRequest;
import com.seatsac.coupon.dto.CouponEventListResponse;
import com.seatsac.coupon.dto.CouponEventResponse;
import com.seatsac.coupon.entity.CouponEventState;
import com.seatsac.coupon.service.CouponEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 API(internal/coupon.internal.openapi.yaml) — 호출자는 ops-api만(NetworkPolicy). ops-api가 ADMIN 그룹을 확인하므로
 * 여기서는 사용자 인증을 하지 않는다. Gateway는 {@code /internal/**}를 라우팅하지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class InternalCouponEventController {

    private final CouponEventService events;

    @PostMapping("/internal/coupon-events")
    @ResponseStatus(HttpStatus.CREATED)
    public CouponEventResponse create(@Valid @RequestBody CouponEventCreateRequest request) {
        return events.create(request);
    }

    @GetMapping("/internal/coupon-events")
    public CouponEventListResponse list(@RequestParam(required = false) CouponEventState state) {
        return events.list(state);
    }
}
