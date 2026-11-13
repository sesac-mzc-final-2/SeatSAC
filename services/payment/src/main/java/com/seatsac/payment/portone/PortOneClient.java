package com.seatsac.payment.portone;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.payment.config.PortOneProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 포트원 V2 REST — 연결 1초·응답 3초, 서킷 {@code portone} (ADR-112, ADR-23·32와 같은 값).
 *
 * <p>부르는 것은 둘뿐이다: 단건 조회({@link #find})와 전액 취소({@link #cancel}). 결제 자체는 브라우저 결제창이
 * 하므로 payment는 포트원에 돈을 움직이는 요청을 「취소」말고는 보내지 않는다.
 *
 * <p>다른 팀 포트원 장애 기록에서 가져온 규칙:
 * <ul>
 *   <li><b>취소 API에는 멱등 키가 없다.</b> 응답을 못 받았거나 「이미 취소됨」류 409면 재호출하지 않고 부르는 쪽이
 *       {@link #find}로 대조한다({@link CancelOutcome#MAYBE_DONE}). 전액만 취소하고 {@code currentCancellableAmount}를
 *       명시해 겹친 요청은 포트원이 하나만 받게 한다</li>
 *   <li>취소 응답 {@code cancellation.status}는 SUCCEEDED·REQUESTED·FAILED — SUCCEEDED만 완료다</li>
 *   <li>오류 본문의 {@code type}은 정규식이 아니라 Jackson으로 읽는다</li>
 *   <li>{@code @Retry}는 쓰지 않는다 — 재호출은 부르는 쪽이 코드로 1회(GET만)</li>
 * </ul>
 *
 * <p>Secret·응답 전체는 로그에 쓰지 않는다. 지표 {@code payment_pg_call_total{op,result}}.
 */
@Slf4j
@Component
public class PortOneClient {

    static final Duration CONNECT = Duration.ofSeconds(1);
    static final Duration READ = Duration.ofSeconds(3);

    /** 「이미 됐을 수 있다」로 보는 포트원 오류 type — 대조 대상이지 확정 실패가 아니다 */
    private static final java.util.Set<String> MAYBE_DONE_TYPES = java.util.Set.of(
        "PAYMENT_ALREADY_CANCELLED", "CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN");

    private final RestClient rest;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String storeId;

    public PortOneClient(PortOneProperties props, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.storeId = blankToNull(props.storeId());
        this.rest = RestClient.builder()
            .baseUrl(props.apiBaseUrl())
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(
                ClientHttpRequestFactorySettings.defaults().withConnectTimeout(CONNECT).withReadTimeout(READ)))
            .defaultHeader(HttpHeaders.AUTHORIZATION, "PortOne " + (props.apiSecret() == null ? "" : props.apiSecret()))
            .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .build();
    }

    /** 단건 조회. 포트원에 없는 결제(결제창을 열지 않았다)는 비어 있음 */
    @CircuitBreaker(name = "portone")
    public Optional<PortOnePayment> find(String paymentId) {
        try {
            PortOnePayment payment = rest.get()
                .uri(builder -> {
                    builder.path("/payments/{paymentId}");
                    if (storeId != null) {
                        builder.queryParam("storeId", storeId);
                    }
                    return builder.build(paymentId);
                })
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    if (res.getStatusCode().value() == 404) {
                        throw new NotFound();
                    }
                    // 401(API Secret)·403(storeId) 같은 설정 오류 — 응답을 못 받은 것과 같이 다룬다
                    throw unavailable("get", "error", "HTTP " + res.getStatusCode().value());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw unavailable("get", "error", "HTTP " + res.getStatusCode().value());
                })
                .body(PortOnePayment.class);
            count("get", "ok");
            return Optional.ofNullable(payment);
        } catch (NotFound e) {
            count("get", "ok");
            return Optional.empty();
        } catch (PortOneUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw unavailable("get", "timeout", e.getClass().getSimpleName());
        } catch (RestClientException e) {
            throw unavailable("get", "error", e.getClass().getSimpleName());
        }
    }

    /**
     * 전액 취소. {@code amount}와 {@code currentCancellableAmount}를 같은 값으로 명시한다 — 포트원이 잔액을 비교해
     * 이미 취소된 결제나 겹친 요청을 거절한다(우리가 멱등을 다시 만들지 않는다).
     *
     * @return {@link CancelOutcome#SUCCEEDED} 취소 완료 · {@link CancelOutcome#REQUESTED} PG가 처리 중(완료 아님) ·
     *         {@link CancelOutcome#MAYBE_DONE} 이미 취소됐을 수 있음(부르는 쪽이 {@link #find}로 대조)
     * @throws PortOneRejectedException 포트원이 확정 거절(아직 결제되지 않음 등)
     * @throws PortOneUnavailableException 응답을 못 받음 — <b>취소가 됐는지 모른다</b>, 부르는 쪽이 대조
     */
    @CircuitBreaker(name = "portone")
    public CancelOutcome cancel(String paymentId, int amount, String reason) {
        Map<String, Object> body = storeId == null
            ? Map.of("amount", amount, "currentCancellableAmount", amount, "reason", reason)
            : Map.of("storeId", storeId, "amount", amount, "currentCancellableAmount", amount, "reason", reason);
        try {
            CancelResponse response = rest.post()
                .uri("/payments/{paymentId}/cancel", paymentId)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw unavailable("cancel", "error", "HTTP " + res.getStatusCode().value());
                })
                .body(CancelResponse.class);
            String status = response == null || response.cancellation() == null ? null : response.cancellation().status();
            count("cancel", "ok");
            if ("SUCCEEDED".equals(status)) {
                return CancelOutcome.SUCCEEDED;
            }
            if ("REQUESTED".equals(status)) {
                return CancelOutcome.REQUESTED;
            }
            // FAILED 또는 모양이 다름 — 확정 거절로 본다
            throw new PortOneRejectedException(status == null ? "UNKNOWN_CANCELLATION" : "CANCELLATION_" + status);
        } catch (RestClientResponseException e) {
            // 4xx — 본문의 type으로 「이미 됐을 수 있다」와 확정 거절을 가른다
            String type = errorType(e.getResponseBodyAsString());
            count("cancel", "ok");
            if (MAYBE_DONE_TYPES.contains(type)) {
                log.info("포트원 취소 거절 — 이미 취소됐을 수 있어 대조한다 paymentId={} type={}", paymentId, type);
                return CancelOutcome.MAYBE_DONE;
            }
            throw new PortOneRejectedException(type == null ? "HTTP_" + e.getStatusCode().value() : type);
        } catch (PortOneUnavailableException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw unavailable("cancel", "timeout", e.getClass().getSimpleName());
        } catch (RestClientException e) {
            throw unavailable("cancel", "error", e.getClass().getSimpleName());
        }
    }

    public enum CancelOutcome {
        SUCCEEDED,
        REQUESTED,
        MAYBE_DONE
    }

    /** 포트원이 확정 거절했다(예: 아직 결제되지 않음). 재시도해도 같다 */
    public static class PortOneRejectedException extends RuntimeException {
        private final String type;

        public PortOneRejectedException(String type) {
            super("포트원 거절: " + type);
            this.type = type;
        }

        public String type() {
            return type;
        }
    }

    /** 포트원 오류 본문 {@code {"type": "...", "message": "..."}} — Jackson으로 읽는다(정규식 함정 회피) */
    String errorType(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            ErrorBody error = objectMapper.readValue(body, ErrorBody.class);
            return error.type();
        } catch (Exception e) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CancelResponse(PortOnePayment.Cancellation cancellation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String type, String message) {
    }

    private static final class NotFound extends RuntimeException {
    }

    private PortOneUnavailableException unavailable(String op, String result, String detail) {
        count(op, result);
        log.warn("포트원 호출 실패 op={} cause={}", op, detail);
        return new PortOneUnavailableException(detail);
    }

    private void count(String op, String result) {
        meterRegistry.counter("payment_pg_call_total", "op", op, "result", result).increment();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
