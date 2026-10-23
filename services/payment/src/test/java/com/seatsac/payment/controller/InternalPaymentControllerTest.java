package com.seatsac.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.web.CommonWebAutoConfiguration;
import com.seatsac.payment.common.PaymentErrorCode;
import com.seatsac.payment.dto.response.PaymentResponse;
import com.seatsac.payment.enums.PaymentCancelReason;
import com.seatsac.payment.enums.PaymentFailureCode;
import com.seatsac.payment.enums.PaymentMethod;
import com.seatsac.payment.enums.PaymentStatus;
import com.seatsac.payment.service.PaymentService;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 내부 API 3개의 HTTP 층 — 상태 코드, 오류 본문(code·details 배열), 형식 검증,
 * <b>카드 번호가 응답·오류에 새지 않는지</b>. 흐름은 {@code PaymentFlowTest}가 본다.
 */
@WebMvcTest(controllers = InternalPaymentController.class)
@ImportAutoConfiguration(CommonWebAutoConfiguration.class)
class InternalPaymentControllerTest {

    private static final String PAYMENT_ID = "3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13";
    private static final Instant NOW = Instant.parse("2026-10-23T03:02:59Z");   // KST 12:02:59
    private static final String CARD_NUMBER = "4111111111111112";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PaymentService paymentService;

    private static String payBody(String paymentId, int amount, String cardNumber) {
        return """
            {"paymentId":"%s","bookingId":5001,"amount":%d,"method":"CARD",
             "card":{"number":"%s","expiry":"12/28","holder":"KIM"}}
            """.formatted(paymentId, amount, cardNumber);
    }

    private static PaymentResponse approved() {
        return PaymentResponse.builder()
            .paymentId(PAYMENT_ID).bookingId(5001L).amount(30_000).method(PaymentMethod.CARD)
            .status(PaymentStatus.APPROVED).cardLast4("1112")
            .approvedAt(Times.toApi(NOW.plusSeconds(1))).createdAt(Times.toApi(NOW))
            .build();
    }

    // --- PAY-01 ---

    @Test
    void payReturnsPaymentWithoutLeakingTheCardNumber() throws Exception {
        when(paymentService.pay(any())).thenReturn(approved());

        mvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                .content(payBody(PAYMENT_ID, 30_000, CARD_NUMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.cardLast4").value("1112"))
            // 계약이 필수로 둔 필드라 키는 있고 값만 null이다 (PaymentJsonTest가 본문으로도 확인)
            .andExpect(jsonPath("$.failureCode").value(Matchers.nullValue()))
            .andExpect(jsonPath("$.createdAt").value("2026-10-23T12:02:59+09:00"))
            .andExpect(content().string(Matchers.not(Matchers.containsString(CARD_NUMBER))));
    }

    /** 거절도 200이다 — 상태로 구분한다 (명세 4장) */
    @Test
    void declinedPaymentIsAlsoTwoHundred() throws Exception {
        when(paymentService.pay(any())).thenReturn(PaymentResponse.builder()
            .paymentId(PAYMENT_ID).bookingId(5001L).amount(30_000).method(PaymentMethod.CARD)
            .status(PaymentStatus.FAILED).cardLast4("0000")
            .failureCode(PaymentFailureCode.CARD_DECLINED).createdAt(Times.toApi(NOW))
            .build());

        mvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                .content(payBody(PAYMENT_ID, 30_000, "4111111111110000")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureCode").value("CARD_DECLINED"));
    }

    @Test
    void paymentIdMustBeUuid() throws Exception {
        mvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                .content(payBody("not-a-uuid", 30_000, CARD_NUMBER)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("paymentId"));

        verify(paymentService, never()).pay(any());
    }

    @Test
    void amountOutOfRangeIsRejectedBeforeService() throws Exception {
        mvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                .content(payBody(PAYMENT_ID, 75_000, CARD_NUMBER)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("amount"));

        verify(paymentService, never()).pay(any());
    }

    /** 카드 형식이 틀리면 400이고, 오류 본문에 카드 번호가 들어가지 않는다 */
    @Test
    void invalidCardIsRejectedWithoutEchoingTheNumber() throws Exception {
        mvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                .content(payBody(PAYMENT_ID, 30_000, "4111")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("card.number"))
            .andExpect(content().string(Matchers.not(Matchers.containsString("4111"))));
    }

    @Test
    void conflictingPaymentIdIsFourZeroNine() throws Exception {
        when(paymentService.pay(any())).thenThrow(PaymentErrorCode.PAYMENT_ID_CONFLICT.exception(
            ErrorDetail.of("paymentId", PAYMENT_ID, "PAYMENT_ID_CONFLICT")));

        mvc.perform(post("/internal/payments").contentType(MediaType.APPLICATION_JSON)
                .content(payBody(PAYMENT_ID, 30_000, CARD_NUMBER)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_CONFLICT"))
            .andExpect(jsonPath("$.details[0].reason").value("PAYMENT_ID_CONFLICT"));
    }

    // --- 조회 ---

    @Test
    void unknownPaymentIsNotFound() throws Exception {
        when(paymentService.get(anyString()))
            .thenThrow(PaymentErrorCode.PAYMENT_NOT_FOUND.exception());

        mvc.perform(get("/internal/payments/{id}", PAYMENT_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.details[0].reason").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void pathPaymentIdMustBeUuid() throws Exception {
        mvc.perform(get("/internal/payments/{id}", "not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details").isArray());

        verify(paymentService, never()).get(anyString());
    }

    // --- PAY-03 ---

    @Test
    void cancelReturnsCanceledPayment() throws Exception {
        when(paymentService.cancel(anyString(), any())).thenReturn(PaymentResponse.builder()
            .paymentId(PAYMENT_ID).bookingId(5001L).amount(30_000).method(PaymentMethod.CARD)
            .status(PaymentStatus.CANCELED).cardLast4("1112")
            .cancelReason(PaymentCancelReason.HOLD_EXPIRED)
            .approvedAt(Times.toApi(NOW)).canceledAt(Times.toApi(NOW.plusSeconds(60)))
            .createdAt(Times.toApi(NOW))
            .build());

        mvc.perform(post("/internal/payments/{id}/cancel", PAYMENT_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"HOLD_EXPIRED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELED"))
            .andExpect(jsonPath("$.cancelReason").value("HOLD_EXPIRED"))
            .andExpect(jsonPath("$.canceledAt").value("2026-10-23T12:03:59+09:00"));
    }

    @Test
    void cancelReasonOutsideTheEnumIsRejected() throws Exception {
        mvc.perform(post("/internal/payments/{id}/cancel", PAYMENT_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BECAUSE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details").isArray());

        verify(paymentService, never()).cancel(anyString(), any());
    }

    @Test
    void cancelingProcessingPaymentIsFourZeroNine() throws Exception {
        when(paymentService.cancel(anyString(), any()))
            .thenThrow(PaymentErrorCode.PAYMENT_PROCESSING.exception(
                ErrorDetail.of("status", "PROCESSING", "PAYMENT_PROCESSING")));

        mvc.perform(post("/internal/payments/{id}/cancel", PAYMENT_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"STALE_PAYMENT\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PAYMENT_NOT_APPROVED"))
            .andExpect(jsonPath("$.details[0].reason").value("PAYMENT_PROCESSING"));
    }
}
