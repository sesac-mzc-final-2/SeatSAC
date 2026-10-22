package com.seatsac.booking.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * BKG-11 멱등 처리를 켠다. 요청 본문이 있는 쓰기 API에만 붙인다 —
 * {@code Idempotency-Key}와 <b>요청 본문 해시</b>로 같은 요청인지 판정하기 때문이다.
 *
 * <p>BKG-02 선점·BKG-10 결제는 {@code required = true}(키가 없으면 400),
 * BKG-03 해제·BKG-07 취소는 상태 전이가 조건부 UPDATE라 자체로 멱등해서 붙이지 않는다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 키가 없을 때 400 {@code VALIDATION_FAILED}로 거절할지 */
    boolean required() default true;
}
