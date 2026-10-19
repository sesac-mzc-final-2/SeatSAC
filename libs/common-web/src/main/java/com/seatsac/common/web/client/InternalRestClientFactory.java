package com.seatsac.common.web.client;

import com.seatsac.common.core.http.Headers;
import com.seatsac.common.web.trace.TraceIdFilter;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/**
 * 내부 호출용 RestClient. 연결 1초 · 응답 3초 기본(ADR-32), traceId·userId 헤더 자동 전달.
 * 서킷·재시도는 호출하는 쪽에서 Resilience4j 애너테이션(@CircuitBreaker)으로 건다.
 *   RestClient payment = factory.create("http://payment:8080");
 *   RestClient booking = factory.create("http://booking:8080", Duration.ofMillis(300)); // catalog→booking 잔여석
 */
public class InternalRestClientFactory {

    public static final Duration CONNECT = Duration.ofSeconds(1);
    public static final Duration READ = Duration.ofSeconds(3);

    public RestClient create(String baseUrl) {
        return create(baseUrl, READ);
    }

    public RestClient create(String baseUrl, Duration readTimeout) {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(CONNECT)
            .withReadTimeout(readTimeout);
        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .requestInterceptor((req, body, exec) -> {
                String traceId = TraceIdFilter.current();
                if (traceId != null) {
                    req.getHeaders().set(Headers.TRACE_ID, traceId);
                }
                String userId = MDC.get("userId");
                if (userId != null) {
                    req.getHeaders().set(Headers.USER_ID, userId);
                }
                return exec.execute(req, body);
            })
            .build();
    }
}
