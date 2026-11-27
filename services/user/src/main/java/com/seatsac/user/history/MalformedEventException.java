package com.seatsac.user.history;

/**
 * 형식이 틀린 이벤트 — JSON이 아니거나 envelope·필수 값이 없다. 재배달해도 같으니 리스너가 로그(ERROR) + 지표를 남기고 지운다
 * (DLQ를 채우지 않는다, coupon {@code MalformedCommandException}과 같은 규칙). DB 오류 등 처리 중 장애는 이 예외가 아니라 재배달 대상이다.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
