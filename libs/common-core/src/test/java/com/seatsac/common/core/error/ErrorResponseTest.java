package com.seatsac.common.core.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** error.v1.json 규약: details는 항상 배열, traceId는 null 불가·32자리. JSON 스키마 검증은 common-test 쪽 테스트에서. */
class ErrorResponseTest {

    @Test
    void detailsDefaultsToEmptyArray() {
        ErrorResponse r = ErrorResponse.of(ErrorCode.NOT_FOUND, "없음", null);
        assertNotNull(r.details());
        assertTrue(r.details().isEmpty());
    }

    @Test
    void traceIdIsNeverNullAndAlways32Hex() {
        assertTrue(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "x", null).traceId().matches("^[0-9a-f]{32}$"));
        assertTrue(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "x", "abcd").traceId().matches("^[0-9a-f]{32}$"));
    }

    @Test
    void detailsAreKept() {
        ErrorResponse r = ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "x", null,
            List.of(ErrorDetail.of("seatIds", "MAX_4")));
        assertEquals("seatIds", r.details().get(0).field());
    }
}
