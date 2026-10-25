package com.seatsac.booking.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.doThrow;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.entity.OutboxMessage;
import com.seatsac.booking.enums.OutboxEventType;
import com.seatsac.booking.enums.OutboxStatus;
import com.seatsac.booking.repository.OutboxMessageRepository;
import com.seatsac.common.test.MySqlContainerSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 3.2.6 아웃박스 릴레이 — 점유·전송·재시도·한도. SQS는 포트({@link NotificationPublisher})를
 * 바꿔 끼워 끊고, 시각은 바꿀 수 있는 Clock으로 앞으로 돌린다.
 *
 * <p>테스트 프로필은 {@code app.outbox.relay-enabled}가 false라 스케줄러 빈이 없다 —
 * 백그라운드 주기가 끼어들지 않으므로 {@code relayOnce()}를 직접 불러 한 바퀴씩 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlContainerSupport.class)
class OutboxRelayFlowTest {

    private static final Instant NOW = Instant.parse("2026-10-25T03:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    /** 앞으로 돌릴 수 있는 Clock. 재시도 간격·점유 만료를 실제로 기다리지 않는다 */
    static class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Autowired
    OutboxRelay relay;
    @MockitoSpyBean
    OutboxMessageRepository outboxRepository;   // 한 건만 DB 예외를 내게 하려고 spy로 받는다
    @Autowired
    MeterRegistry meterRegistry;
    @Autowired
    Clock clock;

    @MockitoBean
    NotificationPublisher publisher;

    private MutableClock testClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void reset() {
        outboxRepository.deleteAll();
        testClock().set(NOW);
        doNothing().when(publisher).publish(anyString(), anyString(), anyString());
    }

    private OutboxMessage save(long bookingId, Instant nextAttemptAt) {
        OutboxMessage message = OutboxMessage.pending(UUID.randomUUID().toString(),
            OutboxEventType.BOOKING_CONFIRMED, bookingId, "{\"bookingId\":" + bookingId + "}",
            "0123456789abcdef0123456789abcdef", nextAttemptAt);
        return outboxRepository.saveAndFlush(message);
    }

    private double counter(String result) {
        return meterRegistry.counter("booking_notification_publish_total", "result", result).count();
    }

    @Test
    @DisplayName("보낼 차례가 된 메시지를 보내고 SENT로 기록한다")
    void sendsDueMessage() throws Exception {
        OutboxMessage saved = save(1001L, NOW);
        double before = counter("sent");

        assertThat(relay.relayOnce()).isEqualTo(1);

        OutboxMessage after = outboxRepository.findById(saved.getOutboxId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(after.getSentAt()).isEqualTo(NOW);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(counter("sent")).isEqualTo(before + 1);

        // 본문은 저장된 값 그대로 보낸다. 다만 MySQL JSON 컬럼이 공백·키 순서를 정규화하므로
        // 문자열이 아니라 JSON으로 비교한다(소비자도 파싱해서 읽는다)
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        then(publisher).should().publish(eq("booking.confirmed"),
            eq("0123456789abcdef0123456789abcdef"), payload.capture());
        assertThat(new ObjectMapper().readTree(payload.getValue()))
            .isEqualTo(new ObjectMapper().readTree("{\"bookingId\":1001}"));
    }

    @Test
    @DisplayName("보낸 메시지는 다음 주기에 다시 보내지 않는다")
    void doesNotResendSentMessage() {
        save(1002L, NOW);
        relay.relayOnce();

        assertThat(relay.relayOnce()).isZero();
        then(publisher).should(times(1)).publish(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("아직 시각이 되지 않은 메시지는 집지 않는다")
    void skipsFutureMessage() {
        save(1003L, NOW.plusSeconds(5));

        assertThat(relay.relayOnce()).isZero();
        then(publisher).should(never()).publish(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("전송이 실패하면 PENDING으로 되돌리고 1·2·4초 뒤에 다시 보낸다")
    void retriesWithBackoff() {
        OutboxMessage saved = save(1004L, NOW);
        doThrow(new IllegalStateException("큐 없음")).when(publisher)
            .publish(anyString(), anyString(), anyString());
        double before = counter("retry");

        assertThat(relay.relayOnce()).isZero();

        OutboxMessage after = outboxRepository.findById(saved.getOutboxId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getAttemptCount()).isEqualTo(1);
        assertThat(after.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));   // 1회 실패 → 1초
        assertThat(after.getLastError()).contains("큐 없음");
        assertThat(counter("retry")).isEqualTo(before + 1);

        // 1초 뒤 두 번째 시도 → 2초 뒤로 밀린다
        testClock().set(NOW.plusSeconds(1));
        relay.relayOnce();
        OutboxMessage second = outboxRepository.findById(saved.getOutboxId()).orElseThrow();
        assertThat(second.getAttemptCount()).isEqualTo(2);
        assertThat(second.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1).plusSeconds(2));
    }

    @Test
    @DisplayName("재시도 한도를 넘기면 FAILED로 두고 더 보내지 않는다")
    void givesUpAfterMaxAttempts() {
        OutboxMessage saved = save(1005L, NOW);
        doThrow(new IllegalStateException("큐 없음")).when(publisher)
            .publish(anyString(), anyString(), anyString());
        double before = counter("failed");

        Instant at = NOW;
        for (int i = 0; i < BookingPolicy.OUTBOX_MAX_ATTEMPTS; i++) {
            testClock().set(at);
            relay.relayOnce();
            at = outboxRepository.findById(saved.getOutboxId()).orElseThrow().getNextAttemptAt();
        }

        OutboxMessage after = outboxRepository.findById(saved.getOutboxId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(after.getAttemptCount()).isEqualTo(BookingPolicy.OUTBOX_MAX_ATTEMPTS);
        assertThat(counter("failed")).isEqualTo(before + 1);

        // FAILED는 다시 집지 않는다
        testClock().set(at.plusSeconds(600));
        assertThat(relay.relayOnce()).isZero();
    }

    @Test
    @DisplayName("점유 중인 메시지는 점유가 만료되기 전에는 다른 파드가 집지 않는다")
    void respectsClaimTimeout() {
        OutboxMessage saved = save(1006L, NOW);
        // 다른 파드가 집은 상태를 만든다 (전송 직전에 멈춘 모양)
        assertThat(outboxRepository.claim(saved.getOutboxId(),
            NOW.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT), NOW,
            BookingPolicy.OUTBOX_MAX_ATTEMPTS, 0)).isEqualTo(1);

        assertThat(relay.relayOnce()).isZero();
        then(publisher).should(never()).publish(anyString(), anyString(), anyString());

        // 점유가 만료되면 이어받아 보낸다 — 파드가 전송 중에 죽은 경우
        testClock().set(NOW.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT).plusSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(outboxRepository.findById(saved.getOutboxId()).orElseThrow().getAttemptCount())
            .isEqualTo(2);   // 점유마다 1씩 — 죽은 파드의 시도도 센다(펜싱)
    }

    @Test
    @DisplayName("점유만 하고 죽어 한도에 닿은 행은 FAILED로 닫고 다시 집지 않는다")
    void closesExhaustedClaim() {
        OutboxMessage saved = save(1007L, NOW);
        // 전송 전에 파드가 죽는 상황을 한도까지 반복한다 — 실패 기록이 남지 않는 경로다
        Instant at = NOW;
        for (int i = 0; i < BookingPolicy.OUTBOX_MAX_ATTEMPTS; i++) {
            assertThat(outboxRepository.claim(saved.getOutboxId(),
                at.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT), at,
                BookingPolicy.OUTBOX_MAX_ATTEMPTS, i)).isEqualTo(1);
            at = at.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT).plusSeconds(1);
        }
        OutboxMessage stuck = outboxRepository.findById(saved.getOutboxId()).orElseThrow();
        assertThat(stuck.getStatus()).isEqualTo(OutboxStatus.SENDING);
        assertThat(stuck.getAttemptCount()).isEqualTo(BookingPolicy.OUTBOX_MAX_ATTEMPTS);
        double before = counter("failed");

        testClock().set(at);
        assertThat(relay.relayOnce()).isZero();

        OutboxMessage after = outboxRepository.findById(saved.getOutboxId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(after.getLastError()).contains("점유 만료");
        assertThat(counter("failed")).isEqualTo(before + 1);
        then(publisher).should(never()).publish(anyString(), anyString(), anyString());

        // 닫힌 뒤에는 보내지 않는다 (예전에는 영원히 재점유됐다)
        testClock().set(at.plusSeconds(600));
        assertThat(relay.relayOnce()).isZero();
        assertThat(outboxRepository.countUnsent()).isZero();
    }

    @Test
    @DisplayName("점유가 넘어간 뒤 늦게 도착한 결과는 다른 파드의 상태를 덮어쓰지 않는다")
    void lateResultDoesNotOverwriteNewerClaim() {
        OutboxMessage saved = save(1008L, NOW);
        // 내 점유(시도 1)
        assertThat(outboxRepository.claim(saved.getOutboxId(),
            NOW.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT), NOW,
            BookingPolicy.OUTBOX_MAX_ATTEMPTS, 0)).isEqualTo(1);
        // 점유가 만료되고 다른 파드가 이어받는다(시도 2)
        Instant later = NOW.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT).plusSeconds(1);
        assertThat(outboxRepository.claim(saved.getOutboxId(),
            later.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT), later,
            BookingPolicy.OUTBOX_MAX_ATTEMPTS, 1)).isEqualTo(1);

        // 늦게 돌아온 내 결과(시도 1)는 반영되지 않는다
        assertThat(outboxRepository.markSent(saved.getOutboxId(), 1, later)).isZero();
        assertThat(outboxRepository.findById(saved.getOutboxId()).orElseThrow().getStatus())
            .isEqualTo(OutboxStatus.SENDING);

        // 이어받은 쪽(시도 2)의 결과는 반영된다
        assertThat(outboxRepository.markSent(saved.getOutboxId(), 2, later)).isEqualTo(1);
        assertThat(outboxRepository.findById(saved.getOutboxId()).orElseThrow().getStatus())
            .isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("조회 시점과 attemptCount가 다르면 점유가 거절된다 — 토큰이 어긋난 전송을 막는다")
    void rejectsClaimWhenAttemptCountMoved() {
        OutboxMessage saved = save(1009L, NOW);
        // 다른 파드가 집었고(1) 그 점유까지 만료된 상태
        assertThat(outboxRepository.claim(saved.getOutboxId(),
            NOW.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT), NOW,
            BookingPolicy.OUTBOX_MAX_ATTEMPTS, 0)).isEqualTo(1);
        Instant later = NOW.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT).plusSeconds(1);

        // 조회 시점 값(0)으로 집으려 하면 거절된다 — 성공시키면 DB는 2, 내 토큰은 1이 되어
        // 결과 기록이 0행이 되고 보낸 메시지가 SENDING에 남는다
        assertThat(outboxRepository.claim(saved.getOutboxId(),
            later.plus(BookingPolicy.OUTBOX_CLAIM_TIMEOUT), later,
            BookingPolicy.OUTBOX_MAX_ATTEMPTS, 0)).isZero();
        assertThat(outboxRepository.findById(saved.getOutboxId()).orElseThrow().getAttemptCount())
            .isEqualTo(1);
    }

    @Test
    @DisplayName("한 주기에 집는 양을 배치 크기로 묶는다")
    void limitsBatchSize() {
        int total = BookingPolicy.OUTBOX_RELAY_BATCH + 10;
        for (int i = 0; i < total; i++) {
            save(2000L + i, NOW);
        }

        assertThat(relay.relayOnce()).isEqualTo(BookingPolicy.OUTBOX_RELAY_BATCH);
        assertThat(outboxRepository.countUnsent()).isEqualTo(10);

        assertThat(relay.relayOnce()).isEqualTo(10);
        assertThat(outboxRepository.countUnsent()).isZero();
    }

    @Test
    @DisplayName("한 건이 DB 예외로 터져도 나머지를 계속 보낸다 — 앞 건이 배치를 막지 않는다")
    void oneFailureDoesNotStopBatch() {
        OutboxMessage first = save(6001L, NOW.minusSeconds(10));   // 먼저 집히는 건
        save(6002L, NOW.minusSeconds(5));
        save(6003L, NOW.minusSeconds(1));
        // 첫 건의 점유에서 DB 오류가 난다. 발행 실패는 relay가 삼켜 재시도로 처리하므로
        // 배치가 끊기는 경우는 이렇게 relay 안쪽에서 터지는 예외다
        doThrow(new DataIntegrityViolationException("점유 실패"))
            .when(outboxRepository).claim(eq(first.getOutboxId()), any(), any(), anyInt(), anyInt());

        assertThat(relay.relayOnce()).isEqualTo(2);   // 나머지 2건은 보냈다
        assertThat(outboxRepository.countUnsent()).isEqualTo(1);   // 첫 건만 남았다
    }

    @Test
    @DisplayName("조회 자체가 실패해도 예외를 밖으로 내지 않는다 — 다음 주기가 멈추면 안 된다")
    void swallowsQueryFailure() {
        doThrow(new DataIntegrityViolationException("조회 실패"))
            .when(outboxRepository).findDue(any(), any());

        assertThat(relay.relayOnce()).isZero();   // 예외가 나가면 fixedDelay 주기가 멈춘다
    }

    @Test
    @DisplayName("오래 기다린 메시지를 먼저 보낸다 — 노드 0대 구간 뒤 밀린 알림")
    void sendsOldestFirst() {
        OutboxMessage old = save(3001L, NOW.minus(Duration.ofHours(9)));
        OutboxMessage recent = save(3002L, NOW.minusSeconds(1));

        List<OutboxMessage> due = outboxRepository.findDue(NOW,
            org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(due).extracting(OutboxMessage::getOutboxId)
            .containsExactly(old.getOutboxId(), recent.getOutboxId());
    }

    @Test
    @DisplayName("보내지 못한 건수를 지표로 노출한다")
    void exposesUnsentGauge() {
        save(4001L, NOW);
        save(4002L, NOW.plusSeconds(60));

        assertThat(meterRegistry.get("booking_outbox_pending").gauge().value()).isEqualTo(2.0);

        relay.relayOnce();
        assertThat(meterRegistry.get("booking_outbox_pending").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("재시도 간격은 1·2·4…60초에서 멈춘다")
    void backoffCapsAtSixtySeconds() {
        assertThat(OutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxRelay.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxRelay.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxRelay.backoff(7)).isEqualTo(Duration.ofSeconds(60));
        assertThat(OutboxRelay.backoff(30)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("한 예매에 알림이 두 건 생기지 않는다 — uk_outbox_event_aggregate")
    void rejectsDuplicateEventPerBooking() {
        save(5001L, NOW);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> save(5001L, NOW))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
