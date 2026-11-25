package com.seatsac.booking.checkout;

/**
 * 형식이 틀린 결과 메시지 — JSON이 아니거나 envelope 필수 값이 없다. 재배달해도 같으니 리스너가 로그(ERROR)를 남기고
 * 지운다(설계 04 5장). 처리 중 다른 곳에서 난 {@link IllegalArgumentException}과 섞이지 않게 따로 둔다 — 그런 예외는
 * 재배달 대상이다.
 */
public class MalformedReplyException extends RuntimeException {

    public MalformedReplyException(String message) {
        super(message);
    }

    public MalformedReplyException(String message, Throwable cause) {
        super(message, cause);
    }
}
