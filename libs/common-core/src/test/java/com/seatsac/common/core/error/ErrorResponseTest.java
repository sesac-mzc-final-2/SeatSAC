package com.seatsac.common.core.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorResponseTest {

    /** 서비스 enum 예시 — 세분 이름과 공통 코드가 다르다 */
    private enum TestErrorCode implements ErrorCode {
        PAYMENT_IN_PROGRESS(CommonResponseCode.BOOKING_STATE_CONFLICT, "결제가 진행 중입니다.");

        private final CommonResponseCode responseCode;
        private final String message;

        TestErrorCode(CommonResponseCode responseCode, String message) {
            this.responseCode = responseCode;
            this.message = message;
        }

        @Override
        public int status() {
            return responseCode.status();
        }

        @Override
        public String responseCode() {
            return responseCode.name();
        }

        @Override
        public String message() {
            return message;
        }
    }

    @Test
    void responseBodyCarriesCommonCodeNotDetailedName() {
        ErrorResponse body = ErrorResponse.of(TestErrorCode.PAYMENT_IN_PROGRESS, "t");

        assertEquals("BOOKING_STATE_CONFLICT", body.code());   // 세분 이름을 code에 넣지 않는다
        assertEquals("결제가 진행 중입니다.", body.message());
        assertEquals(409, TestErrorCode.PAYMENT_IN_PROGRESS.status());
        assertEquals("PAYMENT_IN_PROGRESS", TestErrorCode.PAYMENT_IN_PROGRESS.name());
    }

    @Test
    void commonCodeIsItsOwnResponseCode() {
        assertEquals("NOT_FOUND", CommonResponseCode.NOT_FOUND.responseCode());
        assertEquals(404, CommonResponseCode.NOT_FOUND.status());
    }

    @Test
    void detailsIsAlwaysAnArray() {
        assertEquals(List.of(), ErrorResponse.of(CommonResponseCode.NOT_FOUND, "t").details());
        assertEquals(List.of(), ErrorResponse.of(CommonResponseCode.NOT_FOUND, "없음", "t", null).details());
        assertEquals(List.of(), new ErrorResponse("NOT_FOUND", "없음", "t", null).details());
    }

    @Test
    void businessExceptionKeepsDetailsAsArrayAndDefaultMessage() {
        BusinessException empty = new BusinessException(CommonResponseCode.UNAUTHORIZED);
        assertEquals(List.of(), empty.details());
        assertEquals(CommonResponseCode.UNAUTHORIZED.message(), empty.getMessage());

        BusinessException e = new BusinessException(TestErrorCode.PAYMENT_IN_PROGRESS,
            List.of(ErrorDetail.of("status", "PENDING", "PAYMENT_IN_PROGRESS")));
        assertEquals(1, e.details().size());
        assertEquals("PENDING", e.details().get(0).value());
        assertEquals("BOOKING_STATE_CONFLICT", e.code().responseCode());
    }

    @Test
    void requiredDetailNamesTheMissingField() {
        ErrorDetail detail = ErrorDetail.required("Idempotency-Key");
        assertEquals("Idempotency-Key", detail.field());
        assertEquals("REQUIRED", detail.reason());
        assertNull(detail.value());
    }
}
