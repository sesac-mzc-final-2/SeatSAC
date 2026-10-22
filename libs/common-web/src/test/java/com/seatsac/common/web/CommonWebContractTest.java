package com.seatsac.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.web.user.AuthUser;
import com.seatsac.common.web.user.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공통 모듈이 명세대로 동작하는지 — 오류 응답 모양(details 항상 배열), X-Trace-Id 32자리,
 * @CurrentUser 주입과 401. 테스트용 컨트롤러로 자동 설정을 통째로 확인한다.
 */
@SpringBootTest(classes = {CommonWebContractTest.TestApp.class, CommonWebContractTest.TestController.class})
@AutoConfigureMockMvc
class CommonWebContractTest {

    private static final String USER = "11111111-1111-4111-8111-111111111111";
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    MockMvc mvc;

    // --- 오류 응답 모양 (①) ---

    @Test
    void businessErrorCarriesDetailsArray() throws Exception {
        mvc.perform(get("/test/business"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details[0].field").value("seatIds"))
            .andExpect(jsonPath("$.details[0].value").value(101))
            .andExpect(jsonPath("$.details[0].reason").value("HELD"));
    }

    @Test
    void unhandledErrorStillHasDetailsArrayNeverNull() throws Exception {
        mvc.perform(get("/test/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("\"details\":null"))));
    }

    @Test
    void validationFailureDoesNotEchoUserInput() throws Exception {
        mvc.perform(post("/test/body").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details[0].field").value("cardNumber"))
            .andExpect(jsonPath("$.details[0].reason").value("NotNull"))
            .andExpect(jsonPath("$.details[0].value").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void missingHeaderIsBadRequestNotServerError() throws Exception {
        mvc.perform(get("/test/header"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value(Headers.IDEMPOTENCY_KEY))
            .andExpect(jsonPath("$.details[0].reason").value("REQUIRED"));
    }

    @Test
    void missingParameterAndTypeMismatchAreBadRequest() throws Exception {
        mvc.perform(get("/test/param"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].reason").value("REQUIRED"));
        mvc.perform(get("/test/param").param("size", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].reason").value("INVALID_TYPE"));
    }

    @Test
    void detailedNameGoesToDetailsAndCommonCodeToResponse() throws Exception {
        mvc.perform(get("/test/detailed"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BOOKING_STATE_CONFLICT"))   // 공통 코드
            .andExpect(jsonPath("$.details[0].field").value("status"))
            .andExpect(jsonPath("$.details[0].value").value("PENDING"))
            .andExpect(jsonPath("$.details[0].reason").value("PAYMENT_IN_PROGRESS"));
    }

    @Test
    void detailsAlwaysCarriesTheDetailedName() throws Exception {
        // reason 원소가 없으면 공통 처리가 하나 넣는다
        mvc.perform(get("/test/boom"))
            .andExpect(jsonPath("$.details[0].reason").value("INTERNAL_ERROR"));
        mvc.perform(get("/test/business"))
            .andExpect(jsonPath("$.details[1].reason").value("SEAT_UNAVAILABLE"));
    }

    @Test
    void parameterValidationIsBadRequest() throws Exception {
        mvc.perform(get("/test/min").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("page"))
            .andExpect(jsonPath("$.details[0].value").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void methodNotAllowedIs405() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/test/me"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void asyncTimeoutHasNoBody() throws Exception {
        mvc.perform(get("/test/async"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(""));
    }

    // --- X-Trace-Id (②) ---

    @Test
    void traceIdIsGeneratedAsThirtyTwoHex() throws Exception {
        String generated = mvc.perform(get("/test/trace"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader(Headers.TRACE_ID);
        org.junit.jupiter.api.Assertions.assertTrue(TraceIds.isValid(generated), generated);
    }

    @Test
    void validTraceIdIsKeptAndWrongOneIsReplaced() throws Exception {
        mvc.perform(get("/test/trace").header(Headers.TRACE_ID, TRACE))
            .andExpect(header().string(Headers.TRACE_ID, TRACE))
            .andExpect(content().string(TRACE));

        String replaced = mvc.perform(get("/test/trace").header(Headers.TRACE_ID, "test-trace"))
            .andReturn().getResponse().getHeader(Headers.TRACE_ID);
        org.junit.jupiter.api.Assertions.assertTrue(TraceIds.isValid(replaced), replaced);
    }

    // --- @CurrentUser (③) ---

    @Test
    void currentUserInjectsSubAsString() throws Exception {
        mvc.perform(get("/test/me").header(Headers.USER_ID, USER).header(Headers.USER_ROLE, Headers.ROLE_ADMIN))
            .andExpect(status().isOk())
            .andExpect(content().string(USER));
    }

    @Test
    void currentUserInjectsAuthUserWithRole() throws Exception {
        mvc.perform(get("/test/me/role").header(Headers.USER_ID, USER).header(Headers.USER_ROLE, Headers.ROLE_ADMIN))
            .andExpect(status().isOk())
            .andExpect(content().string(USER + ":ADMIN:true"));
    }

    @Test
    void missingUserHeaderIsUnauthorized() throws Exception {
        mvc.perform(get("/test/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.details[0].field").value(Headers.USER_ID))
            .andExpect(jsonPath("$.details[0].reason").value("USER_HEADER_MISSING"));
    }

    @Test
    void malformedUserHeaderIsUnauthorized() throws Exception {
        mvc.perform(get("/test/me").header(Headers.USER_ID, "abc"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.details[0].reason").value("USER_HEADER_INVALID"));
    }

    @Test
    void optionalCurrentUserIsEmptyWithoutHeader() throws Exception {
        mvc.perform(get("/test/public"))
            .andExpect(status().isOk())
            .andExpect(content().string("anonymous"));
    }

    /** 패키지를 스캔하지 않는다 — 자동 설정만 켜서 공통 모듈이 스스로 붙는지 확인한다 */
    @EnableAutoConfiguration
    static class TestApp {
    }

    /** 서비스별 ErrorCode enum 예시 (booking의 BookingErrorCode와 같은 모양) */
    enum TestErrorCode implements ErrorCode {
        PAYMENT_IN_PROGRESS(CommonResponseCode.BOOKING_STATE_CONFLICT, "결제가 진행 중인 예매입니다.");

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

    @RestController
    @org.springframework.validation.annotation.Validated
    static class TestController {

        @GetMapping("/test/business")
        String business() {
            throw new BusinessException(CommonResponseCode.SEAT_UNAVAILABLE, "이미 선택된 좌석이 있습니다.",
                List.of(ErrorDetail.of("seatIds", 101L, "HELD")));
        }

        /** 세분 이름(PAYMENT_IN_PROGRESS) ≠ 공통 코드(BOOKING_STATE_CONFLICT) */
        @GetMapping("/test/detailed")
        String detailed() {
            throw new BusinessException(TestErrorCode.PAYMENT_IN_PROGRESS,
                List.of(ErrorDetail.of("status", "PENDING", "PAYMENT_IN_PROGRESS")));
        }

        @GetMapping("/test/min")
        String min(@RequestParam @jakarta.validation.constraints.Min(0) int page) {
            return String.valueOf(page);
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("기대하지 않은 예외");
        }

        @PostMapping("/test/body")
        String body(@Valid @RequestBody CardRequest req) {
            return "ok";
        }

        @GetMapping("/test/header")
        String header(@RequestHeader(Headers.IDEMPOTENCY_KEY) String key) {
            return key;
        }

        @GetMapping("/test/param")
        String param(@RequestParam int size) {
            return String.valueOf(size);
        }

        @GetMapping("/test/trace")
        String trace() {
            return com.seatsac.common.web.trace.TraceIdFilter.current();
        }

        @GetMapping("/test/me")
        String me(@CurrentUser String userId) {
            return userId;
        }

        @GetMapping("/test/me/role")
        String meRole(@CurrentUser AuthUser user) {
            return user.userId() + ":" + user.role() + ":" + user.isAdmin();
        }

        @GetMapping("/test/public")
        String open(@CurrentUser Optional<String> userId) {
            return userId.orElse("anonymous");
        }

        @GetMapping("/test/async")
        String async() {
            throw new org.springframework.web.context.request.async.AsyncRequestTimeoutException();
        }

        record CardRequest(@NotNull String cardNumber) {
        }
    }
}
