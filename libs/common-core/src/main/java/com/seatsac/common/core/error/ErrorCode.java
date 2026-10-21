package com.seatsac.common.core.error;

/**
 * 오류 코드 계약 — 공통 모듈 문서 2장. 두 층으로 나눈다.
 *
 * <ul>
 *   <li>{@link #responseCode()} — 응답 {@code code}로 나가는 <b>공통 코드</b>. 화면이 분기하는 값
 *       ({@link CommonResponseCode})</li>
 *   <li>{@link #name()} — <b>세분 이름</b>(enum 상수). {@code details[].reason}, MDC {@code errorCode},
 *       지표 라벨에 쓴다</li>
 * </ul>
 *
 * 서비스는 {@code XxxErrorCode implements ErrorCode} enum을 만들어 쓴다.
 * 공통 처리가 만드는 오류({@code VALIDATION_FAILED}, {@code NOT_FOUND}, {@code INTERNAL_ERROR} 등)는
 * {@link CommonResponseCode}를 그대로 쓰며, 이때는 세분 이름과 공통 코드가 같다.
 */
public interface ErrorCode {

    /** 세분 이름 (enum 상수 이름) */
    String name();

    /** HTTP 상태 */
    int status();

    /** 응답 {@code code} — 공통 코드 */
    String responseCode();

    /** 사용자에게 보여도 되는 문장 */
    String message();
}
