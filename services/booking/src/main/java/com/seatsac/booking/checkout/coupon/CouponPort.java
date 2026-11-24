package com.seatsac.booking.checkout.coupon;

import java.util.Optional;

/**
 * saga가 coupon에 바라는 것 전부(설계 03, D12). coupon의 메시지 이름·필드·큐는 어댑터 밖으로 나오지 않는다.
 * 모든 메서드는 호출 트랜잭션 안에서 outbox 행만 만들거나 즉시 결과를 준다 — 외부 I/O 없음.
 */
public interface CouponPort {

    /** 예약 요청. 즉시 결정되면 결과, 비동기면 empty(결과는 나중에 결과 메시지로) */
    Optional<CouponReply> reserve(String checkoutId, Long bookingId, String userId, String couponIssueId,
                                  int originalAmount);

    /** 확정(사용). 멱등, 실패해도 saga는 CONFIRMED */
    void use(String checkoutId, Long bookingId, String couponIssueId);

    /** 반환(보상). 멱등, 예약 성공 여부를 몰라도 보낼 수 있다 */
    void release(String checkoutId, Long bookingId, String couponIssueId);

    sealed interface CouponReply permits Reserved, Rejected {
        String checkoutId();
    }

    record Reserved(String checkoutId, Discount discount) implements CouponReply {
    }

    record Rejected(String checkoutId, String reason) implements CouponReply {
    }
}
