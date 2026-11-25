package com.seatsac.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import com.seatsac.coupon.repository.CouponEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 기동 확인 — Flyway가 올라가고 엔티티 매핑이 스키마와 맞는지({@code ddl-auto=validate}) */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class CouponApplicationTest {

    @Autowired
    CouponEventRepository events;

    @Test
    void contextLoadsAndSchemaMatchesEntities() {
        assertThat(events.count()).isNotNegative();
    }
}
