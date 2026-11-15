package com.seatsac.payment.controller;

import com.seatsac.payment.dto.request.PaymentCancelRequest;
import com.seatsac.payment.dto.request.PaymentCreateRequest;
import com.seatsac.payment.dto.response.PaymentResponse;
import com.seatsac.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 API 4개 — `contracts/openapi/internal/payment.internal.openapi.yaml`.
 *
 * <p><b>외부에 노출하지 않는다.</b> Gateway는 `/api/payments/**`를 404로 막고, NetworkPolicy가
 * {@code booking → payment}만 허용한다(명세 1장). 사용자 ID는 받지 않는다 — 추적은
 * {@code bookingId}와 {@code X-Trace-Id}로 한다. 포트원 웹훅은 {@link PortOneWebhookController}가 따로 받는다.
 *
 * <p>네 API 모두 같은 {@link PaymentResponse} 모양을 돌려주고, 승인·거절·{@code PROCESSING}은
 * 전부 200이다(상태로 구분).
 */
@Validated
@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    /** 경로의 결제 ID가 UUID 형식이 아니면 400 {@code VALIDATION_FAILED} */
    private static final String UUID_PATTERN =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final PaymentService paymentService;

    /** PAY-01 결제 생성 — CARD는 모의 승인까지, TOSSPAY는 PROCESSING 행 + 결제창 값 */
    @PostMapping
    public PaymentResponse pay(@Valid @RequestBody PaymentCreateRequest request) {
        return paymentService.pay(request);
    }

    /** 결제 상태 조회 — Booking이 타임아웃·만료 처리에서 결과를 다시 확인한다 (포트원은 부르지 않음) */
    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable @Pattern(regexp = UUID_PATTERN) String paymentId) {
        return paymentService.get(paymentId);
    }

    /** PAY-04 포트원 결제 확인 — 단건 조회로 status·금액을 검증해 APPROVED·FAILED를 정한다 (TOSSPAY만) */
    @PostMapping("/{paymentId}/verify")
    public PaymentResponse verify(@PathVariable @Pattern(regexp = UUID_PATTERN) String paymentId) {
        return paymentService.verify(paymentId);
    }

    /** PAY-03 결제 취소 (환불). 이미 취소된 결제도 200이다 */
    @PostMapping("/{paymentId}/cancel")
    public PaymentResponse cancel(@PathVariable @Pattern(regexp = UUID_PATTERN) String paymentId,
                                  @Valid @RequestBody PaymentCancelRequest request) {
        return paymentService.cancel(paymentId, request.reason());
    }
}
