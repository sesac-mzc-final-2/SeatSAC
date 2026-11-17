package com.seatsac.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * GW-01 하위 서비스 연결 풀. 하위 서비스(Tomcat)는 60초 쉰 keep-alive 연결을 닫는다.
 * 풀이 그보다 오래 연결을 들고 있으면 닫힌 연결을 꺼내 써 503(PrematureClose)이 난다(11/17 catalog 5xx 2건).
 * 실제 61초 대기 대신 application.yml 값이 바인딩됐는지와 60초보다 짧은지를 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayHttpClientPoolTest {

    private static final Duration UPSTREAM_KEEP_ALIVE = Duration.ofSeconds(60); // Tomcat keepAliveTimeout 기본값

    @Autowired
    HttpClientProperties properties;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.cognito.issuer-uri", () -> "http://localhost:1/pool"); // 호출하지 않는다
        registry.add("app.cognito.client-id", () -> "test-client");
    }

    @Test
    void 유휴_연결은_하위_서비스_keepAlive보다_먼저_버린다() {
        Duration maxIdle = properties.getPool().getMaxIdleTime();

        assertThat(maxIdle).isEqualTo(Duration.ofSeconds(30));
        assertThat(maxIdle).isLessThan(UPSTREAM_KEEP_ALIVE);
    }
}
