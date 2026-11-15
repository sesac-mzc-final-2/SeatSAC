package com.seatsac.payment.portone;

import com.seatsac.payment.config.PortOneProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 포트원 V2 웹훅 서명 검증 — Standard Webhooks (ADR-112).
 *
 * <p>헤더 {@code webhook-id}·{@code webhook-timestamp}(epoch 초)·{@code webhook-signature}({@code v1,<base64>}가
 * 공백으로 여러 개). 서명 대상은 {@code "{id}.{timestamp}.{원본 본문}"}, HMAC-SHA256, 키는 웹훅 시크릿
 * ({@code whsec_} 접두사 뒤 base64). 시각 차이 5분을 넘으면 거부(재생 공격).
 *
 * <p><b>원본 본문 그대로</b> 검증해야 한다 — JSON으로 파싱한 뒤 다시 직렬화하면 서명이 어긋난다.
 * 시크릿이 비어 있으면 모든 웹훅을 거부한다(설정 누락을 조용히 통과시키지 않는다).
 */
@Slf4j
@Component
public class WebhookSignatureVerifier {

    static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final String SECRET_PREFIX = "whsec_";

    private final byte[] secret;
    private final Clock clock;

    public WebhookSignatureVerifier(PortOneProperties props, Clock clock) {
        this.secret = decodeSecret(props.webhookSecret());
        this.clock = clock;
    }

    public boolean verify(String id, String timestamp, String signatureHeader, String rawBody) {
        if (secret.length == 0 || isBlank(id) || isBlank(timestamp) || isBlank(signatureHeader) || rawBody == null) {
            return false;
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        Instant sent = Instant.ofEpochSecond(epochSeconds);
        if (Duration.between(sent, clock.instant()).abs().compareTo(TOLERANCE) > 0) {
            log.warn("웹훅 타임스탬프가 허용 범위를 벗어남 id={}", id);
            return false;
        }
        byte[] expected = sign(id + "." + timestamp.trim() + "." + rawBody);
        for (String entry : signatureHeader.trim().split("\\s+")) {
            int comma = entry.indexOf(',');
            if (comma < 0 || !"v1".equals(entry.substring(0, comma))) {
                continue;
            }
            byte[] given;
            try {
                given = Base64.getDecoder().decode(entry.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, given)) {
                return true;
            }
        }
        return false;
    }

    /** 테스트가 같은 알고리즘으로 서명을 만들 때 쓴다 */
    public String signatureFor(String id, String timestamp, String rawBody) {
        return "v1," + Base64.getEncoder().encodeToString(sign(id + "." + timestamp + "." + rawBody));
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256을 쓸 수 없다", e);
        }
    }

    /** {@code whsec_<base64>} → 키 바이트. 접두사가 없으면 base64로, 그것도 아니면 문자열 그대로 */
    static byte[] decodeSecret(String configured) {
        if (configured == null || configured.isBlank()) {
            return new byte[0];
        }
        String raw = configured.startsWith(SECRET_PREFIX) ? configured.substring(SECRET_PREFIX.length()) : configured;
        try {
            return Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            return raw.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
