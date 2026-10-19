package com.seatsac.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** ElastiCache와 같은 Valkey 8.2 (keyspace 알림 켬). Spring Data Redis 자동 연결. */
@TestConfiguration(proxyBeanMethods = false)
public class ValkeyContainerSupport {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> valkey() {
        return new GenericContainer<>(DockerImageName.parse("valkey/valkey:8.1"))
            .withExposedPorts(6379)
            .withCommand("valkey-server", "--notify-keyspace-events", "Ex");
    }
}
