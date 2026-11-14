package com.seatsac.payment.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 포트원 V2 설정 — 이름은 {@code docs/service-runtime-contract.md} 3장 그대로 (ADR-112).
 *
 * <p>{@code apiSecret}·{@code webhookSecret}은 K8s Secret {@code payment-secrets}에서 오고 로그·응답에 쓰지 않는다.
 * {@code storeId}·{@code channelKeyTosspay}는 브라우저 SDK에 넘기는 공개 값이라 ConfigMap에 둔다.
 * {@code enabled=false}면 TOSSPAY 요청은 503 {@code PORTONE_DISABLED}이고 포트원을 부르는 스윕도 돌지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "app.portone")
public record PortOneProperties(
    boolean enabled,
    @NotBlank String apiBaseUrl,
    String apiSecret,
    String webhookSecret,
    String storeId,
    String channelKeyTosspay,
    /** 결제창을 끝내지 않고 떠난 PROCESSING을 닫기까지 — 선점 TTL(300초) + 유예 */
    @NotNull Duration pendingTimeout
) {
}
