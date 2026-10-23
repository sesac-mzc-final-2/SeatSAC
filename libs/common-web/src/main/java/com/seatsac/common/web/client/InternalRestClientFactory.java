package com.seatsac.common.web.client;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.http.Headers;
import com.seatsac.common.core.trace.TraceKeys;
import com.seatsac.common.web.trace.TraceIdFilter;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * 내부 호출용 {@code RestClient}. 연결 1초·응답 3초(ADR-32), {@code X-Trace-Id}·{@code X-User-Id} 전파,
 * <b>타임아웃·연결 오류·상대 5xx를 503 {@code SERVICE_UNAVAILABLE}로 바꿔</b> 던진다.
 *
 * <p>서킷은 여기서 걸지 않는다. 호출별 처리(같은 ID 1회 재호출, 캐시 폴백)가 달라서
 * {@code CatalogClient}·{@code PaymentClient}에 이름 붙인 인스턴스로 건다
 * ({@code @CircuitBreaker(name = "catalog")} / {@code "payment"}, 실패율 50%·10초 열림).
 *
 * <p>4xx는 바꾸지 않는다 — 404·409는 호출하는 쪽이 뜻을 아는 값이다.
 * 세분 이름({@code CATALOG_UNAVAILABLE}·{@code PAYMENT_UNAVAILABLE})이 필요하면 호출하는 쪽에서
 * 이 예외를 잡아 자기 {@code ErrorCode}로 다시 던진다.
 *
 * <pre>
 * RestClient payment = factory.create("http://payment:8080");
 * RestClient booking = factory.create("http://booking:8080", Duration.ofMillis(300)); // 잔여석 응답 300ms
 * RestClient counts  = factory.create("http://booking:8080", Duration.ofMillis(300), Duration.ofMillis(300));
 *                      // 연결까지 포함해 300ms 예산(catalog → booking 잔여석, 계약 "300ms 전체")
 * </pre>
 */
public class InternalRestClientFactory {

    public static final Duration CONNECT = Duration.ofSeconds(1);
    public static final Duration READ = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(InternalRestClientFactory.class);

    public RestClient create(String baseUrl) {
        return create(baseUrl, READ);
    }

    /** 연결 타임아웃은 기본 1초({@link #CONNECT}) */
    public RestClient create(String baseUrl, Duration readTimeout) {
        return create(baseUrl, CONNECT, readTimeout);
    }

    /**
     * 연결·응답 타임아웃을 모두 지정한다. 호출 전체 예산이 연결 기본값(1초)보다 짧을 때 쓴다 —
     * 연결이 느린 상대에게 기본값을 그대로 두면 응답 타임아웃을 줄여도 예산을 넘긴다.
     */
    public RestClient create(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        var settings = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
            .requestInterceptor((req, body, exec) -> {
                String traceId = TraceIdFilter.current();
                if (traceId != null) {
                    req.getHeaders().set(Headers.TRACE_ID, traceId);
                }
                String userId = MDC.get(TraceKeys.MDC_USER_ID);
                if (userId != null) {
                    req.getHeaders().set(Headers.USER_ID, userId);
                }
                try {
                    return exec.execute(req, body);
                } catch (IOException e) {
                    // 타임아웃·연결 거부 — 상대가 응답하지 못한 경우
                    log.warn("internal call failed uri={} cause={}: {}", req.getURI(),
                        e.getClass().getSimpleName(), e.getMessage());
                    throw unavailable();
                }
            })
            .defaultStatusHandler(HttpStatusCode::is5xxServerError, (req, res) -> {
                log.warn("internal call returned {} uri={}", res.getStatusCode(), req.getURI());
                throw unavailable();
            })
            .build();
    }

    private static BusinessException unavailable() {
        return new BusinessException(CommonResponseCode.SERVICE_UNAVAILABLE);
    }
}
