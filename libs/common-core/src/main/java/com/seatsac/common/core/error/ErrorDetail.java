package com.seatsac.common.core.error;

/**
 * 오류 응답의 {@code details[]} 원소 — API 명세서 1-4. {@code details}는 항상 배열이고
 * 원소는 {@code {field, value, reason}}이다.
 *
 * <ul>
 *   <li>{@code field}  — 문제가 된 요청 필드·헤더 이름 (없으면 null)</li>
 *   <li>{@code value}  — 화면이 쓸 보조 값(좌석 ID, 진행 중 예매 ID, 현재 상태 등).
 *       <b>사용자가 보낸 값을 그대로 담지 않는다</b> — 카드 번호 같은 민감값이 섞인다</li>
 *   <li>{@code reason} — 서비스별 세분 오류 이름 (예: {@code HOLD_EXPIRED})</li>
 * </ul>
 */
public record ErrorDetail(String field, Object value, String reason) {

    /** 세분 이름만 담는다 */
    public static ErrorDetail reason(String reason) {
        return new ErrorDetail(null, null, reason);
    }

    /** 필드와 보조 값만 담는다 (예: bookingId, expiresAt) */
    public static ErrorDetail of(String field, Object value) {
        return new ErrorDetail(field, value, null);
    }

    public static ErrorDetail of(String field, Object value, String reason) {
        return new ErrorDetail(field, value, reason);
    }

    /** 필수 값 누락 — 예: Idempotency-Key 헤더 */
    public static ErrorDetail required(String field) {
        return new ErrorDetail(field, null, "REQUIRED");
    }
}
