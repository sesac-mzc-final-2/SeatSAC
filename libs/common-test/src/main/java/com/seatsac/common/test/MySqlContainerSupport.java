package com.seatsac.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

/**
 * RDS와 같은 MySQL 8.4. 테스트에 {@code @Import(MySqlContainerSupport.class)} 로 붙인다.
 * 시간대는 RDS 파라미터 그룹({@code time_zone = UTC})과 같게 고정한다 — 도커 호스트 설정에
 * 따라 만료 비교가 달라지지 않게. 운영 JDBC URL의 {@code connectionTimeZone=UTC&
 * forceConnectionTimeZoneToSession=true}는 &#64;ServiceConnection이 URL을 직접 만들기 때문에
 * 여기서는 걸리지 않는다(3.2.5 만료 테스트에서 전용으로 확인한다).
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerSupport {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>("mysql:8.4")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                "--default-time-zone=+00:00");
    }
}
