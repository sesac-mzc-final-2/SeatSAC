package com.seatsac.payment.saga;

/**
 * 형식이 틀린 명령 — JSON이 아니거나 envelope·필수 ID가 없거나 모르는 {@code type}. 재배달해도 같으니
 * 리스너가 로그(ERROR) + 지표를 남기고 지운다(설계 04 5장, DLQ를 채우지 않는다). 처리 중 다른 곳에서 난
 * {@link IllegalArgumentException}과 섞이지 않게 따로 둔다 — 그런 예외는 재배달 대상이다.
 */
public class MalformedCommandException extends RuntimeException {

    public MalformedCommandException(String message) {
        super(message);
    }

    public MalformedCommandException(String message, Throwable cause) {
        super(message, cause);
    }
}
