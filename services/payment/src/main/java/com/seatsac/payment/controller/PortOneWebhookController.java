package com.seatsac.payment.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.payment.common.PaymentErrorCode;
import com.seatsac.payment.portone.WebhookSignatureVerifier;
import com.seatsac.payment.service.PortOneWebhookService;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PAY-05 포트원 웹훅 — `contracts/openapi/payment.openapi.yaml`. 경로는 CloudFront `/api/webhooks/portone` →
 * gateway(인증 없음, StripPrefix) → 여기. 외부에 열린 payment 경로는 이것 하나다.
 *
 * <p>순서: ① 원본 본문으로 서명 검증(실패 401, 본문은 읽지 않는다) ② type·paymentId만 꺼낸다 ③ 본문의 상태는
 * 믿지 않고 {@link PortOneWebhookService}가 포트원 단건 조회로 다시 확인한다. 포트원은 2xx가 아니면 재발송하므로
 * 처리는 멱등이고, 우리가 모르는 결제·type은 200으로 끝낸다(재발송을 부르지 않는다).
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/portone")
@RequiredArgsConstructor
public class PortOneWebhookController {

    private static final Pattern UUID = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final WebhookSignatureVerifier verifier;
    private final PortOneWebhookService webhookService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader(name = "webhook-id", required = false) String id,
                                        @RequestHeader(name = "webhook-timestamp", required = false) String timestamp,
                                        @RequestHeader(name = "webhook-signature", required = false) String signature,
                                        @RequestBody String rawBody) {
        if (!verifier.verify(id, timestamp, signature, rawBody)) {
            throw PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID.exception();
        }
        WebhookBody body = parse(rawBody);
        String paymentId = body.data() == null ? null : body.data().paymentId();
        if (body.type() == null || paymentId == null) {
            throw PaymentErrorCode.INVALID_WEBHOOK.exception();
        }
        if (!UUID.matcher(paymentId).matches()) {
            // booking이 발급한 UUID가 아니다 — 우리 결제가 아니다(다른 가맹점·콘솔 테스트). 재발송을 막기 위해 200
            log.warn("웹훅의 paymentId가 UUID가 아니라 무시한다 type={}", body.type());
            return ResponseEntity.ok().build();
        }
        webhookService.handle(body.type(), paymentId);
        return ResponseEntity.ok().build();
    }

    private WebhookBody parse(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, WebhookBody.class);
        } catch (Exception e) {
            throw PaymentErrorCode.INVALID_WEBHOOK.exception();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WebhookBody(String type, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Data(String paymentId, String storeId, String transactionId) {
        }
    }
}
