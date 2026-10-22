package com.seatsac.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 로컬·테스트용 Valkey (keyspace 알림 켬). Spring Data Redis 자동 연결.
 *
 * <p>기준 문서의 「Valkey 8.2」는 <b>ElastiCache 엔진 버전</b>이고 Docker Hub에는 8.2 태그가 없다
 * (8.1 다음이 9 계열). 그래서 가장 가까운 {@code 8.1.10}으로 고정한다 — 우리가 쓰는 기능
 * (Lua, keyspace {@code Ex}, Pub/Sub, Sorted Set)은 두 버전에서 동일하다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ValkeyContainerSupport {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> valkey() {
        return new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1.10"))
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--notify-keyspace-events", "Ex");
    }
}
