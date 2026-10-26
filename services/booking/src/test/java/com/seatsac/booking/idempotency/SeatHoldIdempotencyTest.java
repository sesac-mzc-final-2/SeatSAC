package com.seatsac.booking.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.dto.request.SeatHoldRequest;
import com.seatsac.booking.dto.response.SeatHoldResponse;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.service.SeatHoldService;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.core.time.Times;
import com.seatsac.common.test.MySqlContainerSupport;
import com.seatsac.common.test.ValkeyContainerSupport;
import com.seatsac.common.test.WithCurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BKG-11 멱등 처리 (명세 2장, `redis-usage.md` 3장). 선점 흐름은 가짜로 두고
 * <b>같은 키가 두 번 왔을 때</b> 무엇이 일어나는지만 본다 — 저장·재생·409·키 삭제.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({MySqlContainerSupport.class, ValkeyContainerSupport.class})
class SeatHoldIdempotencyTest {

    /** 멱등 키로 보내는 UUID. 비밀 값이 아니라 요청 식별자다 */
    private static final String HOLD_REQUEST = "11111111-1111-4111-8111-222222222222";
    private static final String OTHER_USER = "22222222-2222-4222-8222-222222222222";
    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    private static final String BODY = "{\"screeningId\":42,\"seatIds\":[101,102]}";

    @Autowired
    MockMvc mvc;

    @Autowired
    StringRedisTemplate redis;

    @MockitoBean
    SeatHoldService seatHoldService;

    @BeforeEach
    void clearKeys() {
        redis.delete(RedisKeys.idempotency(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST));
        redis.delete(RedisKeys.idempotency(OTHER_USER, HOLD_REQUEST));
    }

    private SeatHoldResponse pending(long bookingId) {
        return SeatHoldResponse.builder()
            .bookingId(bookingId).screeningId(42L).status(BookingStatus.PENDING)
            .seatIds(List.of(101L, 102L)).seatLabels(List.of("J7", "J8"))
            .seatCount(2).amount(30_000)
            .expiresAt(Times.toApi(NOW.plusSeconds(300))).createdAt(Times.toApi(NOW))
            .build();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder hold(
        String userId, String key, String body) {
        return post("/seats/hold").contentType(MediaType.APPLICATION_JSON).content(body)
            .with(WithCurrentUser.user(userId)).with(WithCurrentUser.idempotencyKey(key));
    }

    // --- 같은 키·같은 본문 ---

    @Test
    void sameKeyAndBodyReplaysFirstResponseWithoutRunningAgain() throws Exception {
        given(seatHoldService.hold(anyString(), any(SeatHoldRequest.class))).willReturn(pending(5001L));

        String first = mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bookingId").value(5001))
            .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        verify(seatHoldService, times(1)).hold(anyString(), any());
    }

    /** 공백·필드 순서가 달라도 같은 요청으로 본다 (해시는 다시 직렬화한 본문으로 만든다) */
    @Test
    void whitespaceAndFieldOrderDoNotChangeTheRequest() throws Exception {
        given(seatHoldService.hold(anyString(), any())).willReturn(pending(5001L));

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY)).andExpect(status().isCreated());
        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST,
                "{ \"seatIds\" : [101, 102],\n  \"screeningId\" : 42 }"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bookingId").value(5001));

        verify(seatHoldService, times(1)).hold(anyString(), any());
    }

    @Test
    void storedKeyLivesFor24Hours() throws Exception {
        given(seatHoldService.hold(anyString(), any())).willReturn(pending(5001L));

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY)).andExpect(status().isCreated());

        Long ttl = redis.getExpire(RedisKeys.idempotency(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST),
            TimeUnit.SECONDS);
        assertThat(ttl).isBetween(BookingPolicy.IDEMPOTENCY_TTL.toSeconds() - 60,
            BookingPolicy.IDEMPOTENCY_TTL.toSeconds());
    }

    /**
     * 처리 중 키는 <b>짧게</b> 산다(선점 TTL과 같은 300초). 처리 도중 파드가 죽어 남은 키가
     * 24시간이면 같은 키로 들어오는 재시도가 하루 동안 409를 받는다.
     */
    @Test
    void inProgressKeyExpiresMuchSoonerThanTheStoredResponse() {
        store.begin(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, "hash-only-for-ttl-check");

        Long inProgressTtl = redis.getExpire(
            RedisKeys.idempotency(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST), TimeUnit.SECONDS);

        assertThat(inProgressTtl).isBetween(
            BookingPolicy.IDEMPOTENCY_IN_PROGRESS_TTL.toSeconds() - 5,
            BookingPolicy.IDEMPOTENCY_IN_PROGRESS_TTL.toSeconds());
        assertThat(BookingPolicy.IDEMPOTENCY_IN_PROGRESS_TTL)
            .isLessThan(BookingPolicy.IDEMPOTENCY_TTL);
    }

    // --- 같은 키·다른 본문 / 처리 중 ---

    @Test
    void sameKeyWithDifferentBodyIsConflict() throws Exception {
        given(seatHoldService.hold(anyString(), any())).willReturn(pending(5001L));
        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY)).andExpect(status().isCreated());

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, "{\"screeningId\":42,\"seatIds\":[103]}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
            .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"))
            .andExpect(jsonPath("$.details[0].reason").value("DIFFERENT_REQUEST"));

        verify(seatHoldService, times(1)).hold(anyString(), any());
    }

    /** 앞 요청이 아직 처리 중이면(IN_PROGRESS만 저장된 상태) 같은 본문이어도 409 */
    @Test
    void sameKeyWhileInProgressIsConflict() throws Exception {
        redis.opsForValue().set(RedisKeys.idempotency(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST),
            "{\"status\":\"IN_PROGRESS\",\"requestHash\":\""
                + hashOf(BODY) + "\",\"httpStatus\":null,\"body\":null}",
            BookingPolicy.IDEMPOTENCY_TTL);

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"))
            .andExpect(jsonPath("$.details[0].reason").value("IN_PROGRESS"));

        verify(seatHoldService, times(0)).hold(anyString(), any());
    }

    /**
     * 처리 중 TTL이 끝난 뒤 같은 키로 새 요청이 들어와 응답을 저장했는데, <b>늦게 끝난 원래
     * 요청</b>이 그것을 덮어쓰면 안 된다. 저장·삭제는 처리권(owner)이 같을 때만 한다.
     */
    @Test
    void lateFinisherCannotOverwriteANewerRequestsStoredResponse() {
        String key = RedisKeys.idempotency(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST);
        String hash = "same-request-hash";

        // 원래 요청이 처리권을 잡는다
        IdempotencyStore.Begin first = store.begin(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, hash);
        assertThat(first.acquired()).isTrue();

        // TTL이 끝난 상황을 만든다 → 새 요청이 처리권을 잡고 응답까지 저장한다
        redis.delete(key);
        IdempotencyStore.Begin second = store.begin(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, hash);
        assertThat(second.acquired()).isTrue();
        store.complete(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, second.owner(), hash, 201,
            "{\"bookingId\":5002}");

        // 늦게 끝난 원래 요청이 저장·삭제를 시도해도 바뀌지 않는다
        store.complete(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, first.owner(), hash, 409,
            "{\"code\":\"SEAT_UNAVAILABLE\"}");
        store.discard(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, first.owner());

        // 저장된 값은 새 요청(두 번째)의 것이다. body는 레코드 안에서 다시 이스케이프된다
        assertThat(redis.opsForValue().get(key))
            .contains("bookingId")
            .contains("5002")
            .contains("\"httpStatus\":201")
            .contains(second.owner())
            .doesNotContain("SEAT_UNAVAILABLE");
    }

    // --- 사용자 분리 ---

    @Test
    void otherUserWithTheSameKeyIsUnaffected() throws Exception {
        given(seatHoldService.hold(anyString(), any())).willReturn(pending(5001L), pending(5002L));

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bookingId").value(5001));

        mvc.perform(hold(OTHER_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bookingId").value(5002));   // 남의 응답을 받지 않는다

        verify(seatHoldService, times(2)).hold(anyString(), any());
    }

    // --- 실패 응답 처리 ---

    /** 4xx는 저장한다 — 같은 키로 다시 와도 같은 거절을 받고, 선점을 다시 시도하지 않는다 */
    @Test
    void clientErrorIsStoredAndReplayed() throws Exception {
        given(seatHoldService.hold(anyString(), any())).willThrow(
            BookingErrorCode.SEAT_UNAVAILABLE.exception(ErrorDetail.of("seatIds", 101L, "HELD")));

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
            .andExpect(jsonPath("$.details[0].reason").value("HELD"));

        verify(seatHoldService, times(1)).hold(anyString(), any());
    }

    /** 5xx면 키를 지워 재시도를 허용한다 (C11) */
    @Test
    void serverErrorClearsTheKeySoRetryRuns() throws Exception {
        given(seatHoldService.hold(anyString(), any()))
            .willThrow(new IllegalStateException("예상 못 한 실패"))
            .willReturn(pending(5001L));

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isInternalServerError());

        assertThat(redis.hasKey(RedisKeys.idempotency(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST))).isFalse();

        mvc.perform(hold(WithCurrentUser.DEFAULT_USER, HOLD_REQUEST, BODY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.bookingId").value(5001));

        verify(seatHoldService, times(2)).hold(anyString(), any());
    }

    @Autowired
    IdempotencyStore store;

    private String hashOf(String body) throws Exception {
        // 저장 값의 해시는 「객체로 읽고 다시 직렬화한 본문」으로 만든다
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Object parsed = mapper.readValue(body, SeatHoldRequest.class);
        return store.hash(mapper.writeValueAsBytes(parsed));
    }
}
