package com.seatsac.common.core.error;

/**
 * 오류 코드 계약 — 공통 모듈 문서 2장. 두 층으로 나눈다.
 *
 * <ul>
 *   <li>{@link #commonCode()} / {@link #responseCode()} — 응답 {@code code}로 나가는 <b>공통 코드</b>.
 *       화면이 분기하는 값 ({@link CommonResponseCode})</li>
 *   <li>{@link #name()} — <b>세분 이름</b>(enum 상수). {@code details[].reason}, MDC {@code errorCode},
 *       지표 라벨에 쓴다</li>
 * </ul>
 *
 * 서비스는 {@code XxxErrorCode implements ErrorCode} enum을 만들어 쓴다.
 * 공통 처리가 만드는 오류({@code VALIDATION_FAILED}, {@code NOT_FOUND}, {@code INTERNAL_ERROR} 등)는
 * {@link CommonResponseCode}를 그대로 쓰며, 이때는 세분 이름과 공통 코드가 같다.
 *
 * <h2>구현 방법</h2>
 * 새 구현은 {@link #commonCode()}와 {@link #message()}만 구현한다. {@link #status()}와
 * {@link #responseCode()}는 공통 코드에서 나오므로 오타나 HTTP 상태 불일치가 생기지 않는다.
 * <pre>{@code
 * PAYMENT_IN_PROGRESS(CommonResponseCode.BOOKING_STATE_CONFLICT, "결제가 진행 중인 예매입니다.");
 * public CommonResponseCode commonCode() { return commonCode; }
 * }</pre>
 * {@link #commonCode()}와 {@link #responseCode()}는 서로를 기본값으로 쓴다. 예전 방식(문자열
 * {@code responseCode()}를 직접 구현)도 그대로 동작하지만, <b>둘 중 하나는 반드시 구현</b>해야 한다
 * (둘 다 빠지면 처음 호출할 때 {@code StackOverflowError}).
 *
 * <p><b>enum만 구현한다.</b> {@link #name()}은 enum이 자동으로 채우는 상수 이름이라는 전제로
 * 로그·지표 라벨에 쓰인다. 일반 클래스나 람다로 구현하면 이름이 매번 달라지거나 비어
 * 로그 검색과 지표 집계가 깨진다.
 */
public interface ErrorCode {

    /** 세분 이름 (enum 상수 이름) */
    String name();

    /**
     * 응답 공통 코드. 새 구현은 이것을 구현한다.
     * 기본값은 {@link #responseCode()} 문자열을 {@link CommonResponseCode}로 바꾼 값(예전 구현 호환용)이다.
     */
    default CommonResponseCode commonCode() {
        return CommonResponseCode.valueOf(responseCode());
    }

    /** HTTP 상태. 기본값은 공통 코드의 상태 */
    default int status() {
        return commonCode().status();
    }

    /** 응답 {@code code} 문자열. 기본값은 공통 코드 이름 */
    default String responseCode() {
        return commonCode().name();
    }

    /** 사용자에게 보여도 되는 문장 */
    String message();
}
