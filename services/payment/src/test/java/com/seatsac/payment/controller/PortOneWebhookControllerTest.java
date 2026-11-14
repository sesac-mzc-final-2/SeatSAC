package com.seatsac.payment.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.common.web.CommonWebAutoConfiguration;
import com.seatsac.payment.config.ClockConfig;
import com.seatsac.payment.config.PortOneConfig;
import com.seatsac.payment.portone.WebhookSignatureVerifier;
import com.seatsac.payment.service.PortOneWebhookService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PAY-05 웹훅의 HTTP 층 — 서명 검증(원본 본문), 본문 형식, 우리 결제가 아닌 것은 200으로 끝냄.
 * 상태 반영은 {@code PortOneWebhookService}(흐름 테스트)가 본다.
 */
@WebMvcTest(controllers = PortOneWebhookController.class)
@ImportAutoConfiguration(CommonWebAutoConfiguration.class)
@Import({WebhookSignatureVerifier.class, PortOneConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
    "app.portone.enabled=true",
    "app.portone.api-base-url=http://127.0.0.1:1",
    "app.portone.webhook-secret=whsec_dGVzdC13ZWJob29rLXNlY3JldC0xMjM0NTY3ODkw", // gitleaks:allow — 테스트용 가짜 값
    "app.portone.pending-timeout=7m"
})
class PortOneWebhookControllerTest {

    private static final String PAYMENT_ID = "3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13";

    @Autowired
    MockMvc mvc;
    @Autowired
    WebhookSignatureVerifier verifier;
    @MockitoBean
    PortOneWebhookService webhookService;

    private static String body(String type, String paymentId) {
        return "{\"type\":\"" + type + "\",\"timestamp\":\"2026-11-16T05:04:10.000Z\",\"data\":{\"paymentId\":\""
            + paymentId + "\",\"storeId\":\"store-1\",\"transactionId\":\"tx\"}}";
    }

    private String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    @Test
    void validSignatureIsAcceptedAndHandedToTheService() throws Exception {
        String body = body("Transaction.Paid", PAYMENT_ID);
        String ts = now();

        mvc.perform(post("/webhooks/portone").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("webhook-id", "msg_1").header("webhook-timestamp", ts)
                .header("webhook-signature", verifier.signatureFor("msg_1", ts, body)))
            .andExpect(status().isOk());

        then(webhookService).should().handle("Transaction.Paid", PAYMENT_ID);
    }

    @Test
    void wrongSignatureIsUnauthorizedAndNothingIsHandled() throws Exception {
        String body = body("Transaction.Paid", PAYMENT_ID);
        String ts = now();

        mvc.perform(post("/webhooks/portone").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("webhook-id", "msg_1").header("webhook-timestamp", ts)
                .header("webhook-signature", verifier.signatureFor("msg_1", ts, body.replace("Paid", "Failed"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.details[0].reason").value("WEBHOOK_SIGNATURE_INVALID"));

        then(webhookService).should(never()).handle(anyString(), anyString());
    }

    @Test
    void missingSignatureHeadersIsUnauthorized() throws Exception {
        mvc.perform(post("/webhooks/portone").contentType(MediaType.APPLICATION_JSON)
                .content(body("Transaction.Paid", PAYMENT_ID)))
            .andExpect(status().isUnauthorized());

        then(webhookService).should(never()).handle(anyString(), anyString());
    }

    @Test
    void paymentIdThatIsNotOursIsAcknowledgedWithoutHandling() throws Exception {
        String body = body("Transaction.Paid", "order-20261116-0001");
        String ts = now();

        mvc.perform(post("/webhooks/portone").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("webhook-id", "msg_2").header("webhook-timestamp", ts)
                .header("webhook-signature", verifier.signatureFor("msg_2", ts, body)))
            .andExpect(status().isOk());

        then(webhookService).should(never()).handle(anyString(), anyString());
    }

    @Test
    void malformedBodyIsBadRequestAfterTheSignaturePasses() throws Exception {
        String body = "{\"type\":\"Transaction.Paid\"}";   // data 없음
        String ts = now();

        mvc.perform(post("/webhooks/portone").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("webhook-id", "msg_3").header("webhook-timestamp", ts)
                .header("webhook-signature", verifier.signatureFor("msg_3", ts, body)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].reason").value("INVALID_WEBHOOK"));
    }
}
