package com.seatsac.booking.checkout;

/** 조건부 전이가 0행 — saga가 그 사이 다른 상태로 넘어갔다(늦은 결과). 트랜잭션을 되돌리려고 던진다 */
class SagaMovedOnException extends RuntimeException {

    SagaMovedOnException(String checkoutId) {
        super("체크아웃이 이미 다른 상태다 checkoutId=" + checkoutId);
    }
}
