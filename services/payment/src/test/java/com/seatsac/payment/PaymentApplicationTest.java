package com.seatsac.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 기동 확인 — Flyway V1이 올라가고 엔티티 매핑이 스키마와 맞는지({@code ddl-auto=validate}).
 * 컬럼 타입이 어긋나면 여기서 먼저 깨진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlContainerSupport.class)
class PaymentApplicationTest {

    @Autowired
    PaymentRepository paymentRepository;

    @Test
    void contextLoadsAndSchemaMatchesEntities() {
        assertThat(paymentRepository.count()).isNotNegative();
    }
}
