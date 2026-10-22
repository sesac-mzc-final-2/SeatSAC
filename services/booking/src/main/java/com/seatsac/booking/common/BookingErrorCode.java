package com.seatsac.booking.common;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import java.util.Arrays;
import java.util.List;

/**
 * booking의 세분 오류 이름 — 명세 2장 「오류 응답」. 공통 모듈 문서 2장의 두 층 구조를 따른다.
 *
 * <ul>
 *   <li>enum 이름 = <b>세분 이름</b> → {@code details[].reason}, MDC {@code errorCode}, 지표 라벨</li>
 *   <li>{@link #commonCode()} = 화면이 분기하는 <b>공통 코드</b>({@link CommonResponseCode}).
 *       HTTP 상태와 응답 {@code code} 문자열은 {@link ErrorCode}의 기본값이 여기서 꺼낸다</li>
 * </ul>
 *
 * 던질 때는 {@code throw BookingErrorCode.SCREENING_NOT_FOUND.exception()} 처럼 쓴다.
 * {@code details}에 세분 이름 원소를 넣는 일은 공통 {@code GlobalExceptionHandler}가 한다.
 *
 * <p>공통 처리가 만드는 오류는 여기 넣지 않는다 — 멱등 키 누락은
 * {@code MissingRequestHeaderException} → 400 {@code VALIDATION_FAILED}로 나간다 (공통 모듈 문서 10장).
 */
public enum BookingErrorCode implements ErrorCode {

    // 400 VALIDATION_FAILED
    INVALID_SEATS(CommonResponseCode.VALIDATION_FAILED, "좌석 선택이 올바르지 않습니다."),
    IDEMPOTENCY_KEY_REQUIRED(CommonResponseCode.VALIDATION_FAILED, "멱등 키가 필요합니다."),
    INVALID_PAYMENT_METHOD(CommonResponseCode.VALIDATION_FAILED, "지원하지 않는 결제 수단입니다."),
    INVALID_STATUS(CommonResponseCode.VALIDATION_FAILED, "조회 조건이 올바르지 않습니다."),
    INVALID_PAGE(CommonResponseCode.VALIDATION_FAILED, "페이지 값이 올바르지 않습니다."),
    INVALID_IDS(CommonResponseCode.VALIDATION_FAILED, "ID 목록이 올바르지 않습니다."),

    // 400 SCREENING_STARTED
    SCREENING_CLOSED(CommonResponseCode.SCREENING_STARTED, "이미 상영이 시작된 회차입니다."),

    // 401 / 403
    USER_HEADER_MISSING(CommonResponseCode.UNAUTHORIZED, "인증 정보가 없습니다."),
    NOT_OWNER(CommonResponseCode.FORBIDDEN, "본인의 예매가 아닙니다."),

    // 404
    BOOKING_NOT_FOUND(CommonResponseCode.NOT_FOUND, "예매를 찾을 수 없습니다."),
    SCREENING_NOT_FOUND(CommonResponseCode.NOT_FOUND, "회차를 찾을 수 없습니다."),

    // 409
    SEAT_UNAVAILABLE(CommonResponseCode.SEAT_UNAVAILABLE, "이미 선택된 좌석이 있습니다."),
    ACTIVE_HOLD_EXISTS(CommonResponseCode.ACTIVE_HOLD_EXISTS, "진행 중인 선점이 있습니다."),
    BOOKING_NOT_PENDING(CommonResponseCode.BOOKING_STATE_CONFLICT, "결제 대기 상태의 예매가 아닙니다."),
    BOOKING_NOT_CONFIRMED(CommonResponseCode.BOOKING_STATE_CONFLICT, "확정된 예매가 아닙니다."),
    PAYMENT_IN_PROGRESS(CommonResponseCode.BOOKING_STATE_CONFLICT, "결제가 진행 중인 예매는 해제할 수 없습니다."),
    HOLD_EXPIRED(CommonResponseCode.BOOKING_STATE_CONFLICT, "선점이 만료되었습니다."),
    PAYMENT_ALREADY_REQUESTED(CommonResponseCode.PAYMENT_ALREADY_REQUESTED, "이미 결제를 요청한 예매입니다."),
    IDEMPOTENCY_KEY_CONFLICT(CommonResponseCode.IDEMPOTENCY_KEY_CONFLICT, "같은 키로 다른 요청을 보냈습니다."),
    IDEMPOTENCY_IN_PROGRESS(CommonResponseCode.IDEMPOTENCY_IN_PROGRESS, "같은 요청이 처리 중입니다. 잠시 후 다시 확인해 주세요."),

    // 503
    PAYMENT_RESULT_UNKNOWN(CommonResponseCode.PAYMENT_RESULT_UNKNOWN,
        "결제 결과를 확인하지 못했습니다. 잠시 후 예매 상태를 확인해 주세요."),
    CATALOG_UNAVAILABLE(CommonResponseCode.SERVICE_UNAVAILABLE,
        "회차 정보를 가져오지 못했습니다. 잠시 후 다시 시도해 주세요."),
    PAYMENT_UNAVAILABLE(CommonResponseCode.SERVICE_UNAVAILABLE,
        "결제 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final CommonResponseCode commonCode;
    private final String message;

    BookingErrorCode(CommonResponseCode commonCode, String message) {
        this.commonCode = commonCode;
        this.message = message;
    }

    @Override
    public CommonResponseCode commonCode() {
        return commonCode;
    }

    @Override
    public String message() {
        return message;
    }

    public BusinessException exception() {
        return new BusinessException(this, message, List.of());
    }

    public BusinessException exception(ErrorDetail... details) {
        return exception(Arrays.asList(details));
    }

    public BusinessException exception(List<ErrorDetail> details) {
        return new BusinessException(this, message, details);
    }
}
