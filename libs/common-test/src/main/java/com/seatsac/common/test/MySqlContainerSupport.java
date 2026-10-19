package com.seatsac.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

/** RDS와 같은 MySQL 8.4. 테스트에 @Import(MySqlContainerSupport.class) 로 붙인다. */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerSupport {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysql() {
        return new MySQLContainer<>("mysql:8.4")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
    }
}
