package com.seatsac.common.core.trace;

/**
 * MDC 키 이름 — 공통 모듈 문서 4장 §7, 5장. 로그 필드 이름이자 로그 검색 키다.
 * 서비스 코드는 문자열 리터럴을 쓰지 않고 이 상수만 쓴다.
 *
 * <p>HTTP 헤더 이름은 {@code Headers.TRACE_ID}({@code X-Trace-Id})이고 이것은 MDC 키
 * ({@code traceId})다. 둘을 바꿔 써도 컴파일되므로 {@code MDC_} 접두사로 구분한다.
 *
 * <p>여기 없는 키는 {@code logback-common.xml}의 {@code includeMdcKeyName} 허용 목록에도 없어
 * JSON 로그에 나오지 않는다. 키를 늘릴 때는 두 곳을 함께 고친다.
 */
public final class TraceKeys {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_USER_ID = "userId";

    /** 요청 밖 작업 이름 (hold-expiry, outbox-relay 등) */
    public static final String MDC_JOB = "job";

    /** 세분 오류 이름 (ErrorCode.name()) */
    public static final String MDC_ERROR_CODE = "errorCode";

    public static final String MDC_BOOKING_ID = "bookingId";
    public static final String MDC_SCREENING_ID = "screeningId";
    public static final String MDC_PAYMENT_ID = "paymentId";

    private TraceKeys() {
    }
}
