package com.seatsac.payment.portone;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.payment.config.PortOneProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-16T05:04:10Z");
    private static final String SECRET = "whsec_dGVzdC13ZWJob29rLXNlY3JldC0xMjM0NTY3ODkw"; // gitleaks:allow — 테스트용 가짜 값
    private static final String BODY = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13\"}}";

    private static WebhookSignatureVerifier verifier(String secret) {
        return new WebhookSignatureVerifier(
            new PortOneProperties(true, "http://127.0.0.1:1", "s", secret, "store", "ch", Duration.ofMinutes(7)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acceptsSignatureMadeWithTheSameSecretAndRawBody() {
        WebhookSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW.getEpochSecond());
        String signature = verifier.signatureFor("msg_1", ts, BODY);

        assertThat(verifier.verify("msg_1", ts, signature, BODY)).isTrue();
        // Standard Webhooks는 서명을 여러 개 보낼 수 있다 — 하나만 맞으면 된다
        assertThat(verifier.verify("msg_1", ts, "v1,AAAA " + signature, BODY)).isTrue();
    }

    @Test
    void rejectsTamperedBodyOtherIdWrongSecretOrMissingHeaders() {
        WebhookSignatureVerifier verifier = verifier(SECRET);
        String ts = String.valueOf(NOW.getEpochSecond());
        String signature = verifier.signatureFor("msg_1", ts, BODY);

        assertThat(verifier.verify("msg_1", ts, signature, BODY.replace("Paid", "Failed"))).isFalse();
        assertThat(verifier.verify("msg_2", ts, signature, BODY)).isFalse();
        assertThat(verifier("whsec_b3RoZXItc2VjcmV0").verify("msg_1", ts, signature, BODY)).isFalse();
        assertThat(verifier.verify(null, ts, signature, BODY)).isFalse();
        assertThat(verifier.verify("msg_1", ts, null, BODY)).isFalse();
        assertThat(verifier.verify("msg_1", ts, "v0," + signature.substring(3), BODY)).isFalse();
    }

    @Test
    void rejectsTimestampOutsideFiveMinutes() {
        WebhookSignatureVerifier verifier = verifier(SECRET);
        String old = String.valueOf(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());

        assertThat(verifier.verify("msg_1", old, verifier.signatureFor("msg_1", old, BODY), BODY)).isFalse();
        assertThat(verifier.verify("msg_1", "not-a-number", "v1,x", BODY)).isFalse();
    }

    @Test
    void emptySecretRejectsEverything() {
        WebhookSignatureVerifier verifier = verifier("");
        String ts = String.valueOf(NOW.getEpochSecond());

        assertThat(verifier.verify("msg_1", ts, "v1,AAAA", BODY)).isFalse();
    }
}
