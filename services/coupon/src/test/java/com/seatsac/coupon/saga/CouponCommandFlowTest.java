package com.seatsac.coupon.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import com.seatsac.coupon.entity.CouponEvent;
import com.seatsac.coupon.entity.CouponIssue;
import com.seatsac.coupon.entity.CouponIssueState;
import com.seatsac.coupon.entity.CouponReservation;
import com.seatsac.coupon.entity.ReservationState;
import com.seatsac.coupon.repository.CouponEventRepository;
import com.seatsac.coupon.repository.CouponIssueRepository;
import com.seatsac.coupon.repository.CouponReservationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * saga 명령 → 결과(22-4). 리스너가 부르는 {@link CouponCommandHandler#handle}을 직접 부르고 발송은 가짜로 바꾼다.
 * 표준 큐의 중복·순서 역전에서도 결과가 같은지, 결과가 계약({@code contracts/events/coupon.*.v1.json})의 필수 필드를 갖는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class CouponCommandFlowTest {

    private static final Path CONTRACTS = Path.of("../../contracts/events");
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    CouponCommandHandler handler;
    @Autowired
    ReplyOutboxRelay relay;
    @Autowired
    ReplyOutboxRepository outbox;
    @Autowired
    ProcessedMessageRepository inbox;
    @Autowired
    CouponReservationRepository reservations;
    @Autowired
    CouponIssueRepository issues;
    @Autowired
    CouponEventRepository events;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    ReplyPublisher publisher;

    private long booking = 9_800L;
    private String owner;
    private CouponIssue coupon;

    @BeforeEach
    void setUp() {
        outbox.deleteAllInBatch();
        inbox.deleteAllInBatch();
        reservations.deleteAllInBatch();
        issues.deleteAllInBatch();
        events.deleteAllInBatch();
        Instant now = Instant.now();
        CouponEvent open = events.saveAndFlush(CouponEvent.create("VIP 선착순 50% 할인", 50, "VIP", 100,
            now.minus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(50)), now.minus(Duration.ofMinutes(20))));
        owner = UUID.randomUUID().toString();
        coupon = issues.saveAndFlush(CouponIssue.issue(open, owner, now.minus(Duration.ofMinutes(5))));
        booking++;
    }

    @Test
    void reservationReservesAndRepliesReservedPerContract() throws IOException {
        String checkout = UUID.randomUUID().toString();
        String command = UUID.randomUUID().toString();

        assertThat(handler.handle(reserve(command, checkout, coupon.getCouponIssueId(), owner)))
            .isEqualTo(CouponCommandHandler.Result.OK);

        assertThat(state(coupon)).isEqualTo(CouponIssueState.RESERVED);
        assertThat(reservations.findById(checkout)).get().extracting(CouponReservation::getState)
            .isEqualTo(ReservationState.RESERVED);
        assertThat(inbox.existsById(command)).isTrue();
        JsonNode reply = onlyReply();
        assertThat(reply.get("type").asText()).isEqualTo("coupon.reserved");
        assertThat(reply.get("correlationId").asText()).isEqualTo(checkout);
        assertThat(reply.get("traceId").asText()).isEqualTo(TRACE);
        assertThat(reply.at("/payload/discountRate").asInt()).isEqualTo(50);
        assertThat(reply.at("/payload/couponIssueId").asText()).isEqualTo(coupon.getCouponIssueId());
        assertThat(reply.at("/payload/bookingId").asLong()).isEqualTo(booking);
        assertMatchesContract(reply);
    }

    @Test
    void sameCommandTwiceIsDuplicateAndRepliesOnce() {
        String body = reserve(UUID.randomUUID().toString(), UUID.randomUUID().toString(), coupon.getCouponIssueId(), owner);

        handler.handle(body);
        assertThat(handler.handle(body)).isEqualTo(CouponCommandHandler.Result.DUPLICATE);

        assertThat(outbox.findByAggregateIdOrderByOutboxIdAsc(booking)).hasSize(1);
    }

    @Test
    void retryOfSameCheckoutWithNewEventIdRepliesTheSameResult() throws IOException {
        String checkout = UUID.randomUUID().toString();
        handler.handle(reserve(UUID.randomUUID().toString(), checkout, coupon.getCouponIssueId(), owner));
        handler.handle(reserve(UUID.randomUUID().toString(), checkout, coupon.getCouponIssueId(), owner));

        List<ReplyOutbox> replies = outbox.findByAggregateIdOrderByOutboxIdAsc(booking);
        assertThat(replies).hasSize(2).extracting(ReplyOutbox::getEventType).containsOnly("coupon.reserved");
        assertThat(state(coupon)).isEqualTo(CouponIssueState.RESERVED);   // 두 번 잡지 않는다
    }

    @Test
    void otherUsersCouponOrUnknownCouponIsNotOwner() throws IOException {
        handler.handle(reserve(UUID.randomUUID().toString(), UUID.randomUUID().toString(), coupon.getCouponIssueId(),
            UUID.randomUUID().toString()));
        assertThat(onlyReply().at("/payload/reason").asText()).isEqualTo("NOT_OWNER");
        outbox.deleteAllInBatch();

        assertThat(handler.handle(reserve(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "not-a-coupon",
            owner))).isEqualTo(CouponCommandHandler.Result.REJECTED);
        JsonNode reply = onlyReply();
        assertThat(reply.get("type").asText()).isEqualTo("coupon.reservation-rejected");
        assertThat(reply.at("/payload/reason").asText()).isEqualTo("NOT_OWNER");
        assertThat(reply.at("/payload/couponIssueId").asText()).isEqualTo("not-a-coupon");
        assertMatchesContract(reply);
        assertThat(state(coupon)).isEqualTo(CouponIssueState.ISSUED);
    }

    @Test
    void secondCheckoutOnTheSameCouponIsNotIssued() {
        handler.handle(reserve(UUID.randomUUID().toString(), UUID.randomUUID().toString(), coupon.getCouponIssueId(), owner));
        outbox.deleteAllInBatch();

        handler.handle(reserve(UUID.randomUUID().toString(), UUID.randomUUID().toString(), coupon.getCouponIssueId(), owner));

        assertThat(onlyReply().at("/payload/reason").asText()).isEqualTo("NOT_ISSUED");
    }

    @Test
    void releaseReturnsReservedCouponToIssued() {
        String checkout = UUID.randomUUID().toString();
        handler.handle(reserve(UUID.randomUUID().toString(), checkout, coupon.getCouponIssueId(), owner));

        handler.handle(command("coupon.release-requested", UUID.randomUUID().toString(), checkout,
            coupon.getCouponIssueId(), ",\"reason\":\"PAYMENT_DECLINED\""));

        assertThat(state(coupon)).isEqualTo(CouponIssueState.ISSUED);
        assertThat(reservations.findById(checkout)).get().extracting(CouponReservation::getState)
            .isEqualTo(ReservationState.RELEASED);
    }

    @Test
    void useMarksUsedAndLaterReleaseIsIgnored() {
        String checkout = UUID.randomUUID().toString();
        handler.handle(reserve(UUID.randomUUID().toString(), checkout, coupon.getCouponIssueId(), owner));

        handler.handle(command("coupon.use-requested", UUID.randomUUID().toString(), checkout, coupon.getCouponIssueId(), ""));
        handler.handle(command("coupon.release-requested", UUID.randomUUID().toString(), checkout,
            coupon.getCouponIssueId(), ""));

        assertThat(state(coupon)).isEqualTo(CouponIssueState.USED);
        assertThat(reservations.findById(checkout)).get().extracting(CouponReservation::getState)
            .isEqualTo(ReservationState.USED);
    }

    @Test
    void releaseArrivingBeforeReservationRejectsTheLateReservation() {
        String checkout = UUID.randomUUID().toString();
        handler.handle(command("coupon.release-requested", UUID.randomUUID().toString(), checkout,
            coupon.getCouponIssueId(), ",\"reason\":\"STEP_TIMEOUT\""));

        handler.handle(reserve(UUID.randomUUID().toString(), checkout, coupon.getCouponIssueId(), owner));

        assertThat(onlyReply().at("/payload/reason").asText()).isEqualTo("ALREADY_RELEASED");
        assertThat(state(coupon)).isEqualTo(CouponIssueState.ISSUED);   // 쿠폰은 잡히지 않았다
    }

    @Test
    void expiredCouponIsRejectedExpired() {
        Instant now = Instant.now();
        CouponEvent ended = events.saveAndFlush(CouponEvent.create("끝남", 30, "VIP", 10,
            now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofHours(3))));
        CouponIssue old = issues.saveAndFlush(CouponIssue.issue(ended, owner, now.minus(Duration.ofHours(1))));

        handler.handle(reserve(UUID.randomUUID().toString(), UUID.randomUUID().toString(), old.getCouponIssueId(), owner));

        assertThat(onlyReply().at("/payload/reason").asText()).isEqualTo("EXPIRED");
    }

    @Test
    void malformedCommandsAreRejectedAsMalformed() {
        assertThatThrownBy(() -> handler.handle("not json")).isInstanceOf(MalformedCommandException.class);
        assertThatThrownBy(() -> handler.handle("""
            {"eventId":"%s","type":"coupon.reservation-requested","payload":{"checkoutId":"%s","couponIssueId":"%s"}}
            """.formatted(UUID.randomUUID(), UUID.randomUUID(), coupon.getCouponIssueId())))
            .isInstanceOf(MalformedCommandException.class);
        assertThatThrownBy(() -> handler.handle(command("coupon.unknown", UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), coupon.getCouponIssueId(), "")))
            .isInstanceOf(MalformedCommandException.class);
    }

    @Test
    void relaySendsTheReplyAndMarksItSent() {
        handler.handle(reserve(UUID.randomUUID().toString(), UUID.randomUUID().toString(), coupon.getCouponIssueId(), owner));

        assertThat(relay.relayOnce()).isEqualTo(1);

        verify(publisher, times(1)).publish(any());
        assertThat(outbox.findByAggregateIdOrderByOutboxIdAsc(booking)).extracting(ReplyOutbox::getStatus)
            .containsExactly(ReplyOutbox.Status.SENT);
    }

    // --- helpers ---

    private String reserve(String eventId, String checkout, String couponIssueId, String userId) {
        return command("coupon.reservation-requested", eventId, checkout, couponIssueId,
            ",\"userId\":\"%s\"".formatted(userId));
    }

    private String command(String type, String eventId, String checkout, String couponIssueId, String extra) {
        return """
            {"eventId":"%s","type":"%s","version":1,"occurredAt":"2026-11-27T14:05:01+09:00","producer":"booking",
             "traceId":"%s","correlationId":"%s",
             "payload":{"checkoutId":"%s","couponIssueId":"%s","bookingId":%d%s}}
            """.formatted(eventId, type, TRACE, checkout, checkout, couponIssueId, booking, extra);
    }

    private CouponIssueState state(CouponIssue issue) {
        return issues.findById(issue.getCouponIssueId()).orElseThrow().getState();
    }

    private JsonNode onlyReply() {
        List<ReplyOutbox> rows = outbox.findByAggregateIdOrderByOutboxIdAsc(booking);
        assertThat(rows).hasSize(1);
        try {
            return objectMapper.readTree(rows.get(0).getPayload());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertMatchesContract(JsonNode reply) throws IOException {
        JsonNode schema = objectMapper.readTree(Files.readString(CONTRACTS.resolve(reply.get("type").asText() + ".v1.json")));
        schema.get("required").forEach(field -> assertThat(reply.has(field.asText())).as(field.asText()).isTrue());
        schema.at("/properties/payload/required").forEach(field ->
            assertThat(reply.get("payload").has(field.asText())).as("payload." + field.asText()).isTrue());
        assertThat(reply.get("producer").asText()).isEqualTo("coupon");
    }
}
