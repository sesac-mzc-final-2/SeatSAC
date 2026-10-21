package com.seatsac.common.core.error;

/**
 * 응답 {@code code}로 나가는 공통 코드 — API 명세서 1-4. 모든 서비스가 같은 값을 쓴다.
 * 새 코드는 <b>추가만</b> 한다(화면이 분기하는 값이라 이름을 바꾸면 화면이 깨진다).
 * 세분 이름이 따로 필요 없는 공통 처리에서는 이 enum을 {@link ErrorCode}로 그대로 쓴다.
 */
public enum CommonResponseCode implements ErrorCode {

    VALIDATION_FAILED(400, "요청 값이 올바르지 않습니다."),
    SCREENING_STARTED(400, "이미 상영이 시작된 회차입니다."),
    UNAUTHORIZED(401, "인증 정보가 없습니다."),
    FORBIDDEN(403, "권한이 없습니다."),
    NOT_FOUND(404, "요청한 정보를 찾을 수 없습니다."),
    /** 명세 1-4 표에는 없다. 405를 500으로 내보내지 않기 위해 추가 (공통 처리 전용) */
    METHOD_NOT_ALLOWED(405, "지원하지 않는 요청 방식입니다."),
    SEAT_UNAVAILABLE(409, "이미 선택된 좌석이 있습니다."),
    ACTIVE_HOLD_EXISTS(409, "진행 중인 선점이 있습니다."),
    BOOKING_STATE_CONFLICT(409, "현재 상태에서는 처리할 수 없습니다."),
    PAYMENT_ALREADY_REQUESTED(409, "이미 결제를 요청한 예매입니다."),
    IDEMPOTENCY_KEY_CONFLICT(409, "같은 키로 다른 요청을 보냈습니다."),
    IDEMPOTENCY_IN_PROGRESS(409, "같은 요청이 처리 중입니다. 잠시 후 다시 확인해 주세요."),
    SCHEDULE_CONFLICT(409, "일정이 겹칩니다."),
    SEAT_SOLD(409, "이미 판매된 좌석입니다."),
    PAYMENT_CONFLICT(409, "결제 상태가 맞지 않습니다."),
    PAYMENT_NOT_APPROVED(409, "승인된 결제가 아닙니다."),
    RATE_LIMITED(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    INTERNAL_ERROR(500, "서버 오류입니다."),
    SERVICE_UNAVAILABLE(503, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."),
    PAYMENT_RESULT_UNKNOWN(503, "결제 결과를 확인하지 못했습니다. 잠시 후 예매 상태를 확인해 주세요."),
    BOOKING_UNAVAILABLE(503, "예매 정보를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String message;

    CommonResponseCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    /** 공통 코드 자신이므로 세분 이름과 같다 */
    @Override
    public String responseCode() {
        return name();
    }

    @Override
    public String message() {
        return message;
    }
}
