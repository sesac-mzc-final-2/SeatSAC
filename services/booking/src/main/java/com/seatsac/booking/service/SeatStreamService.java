package com.seatsac.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsac.booking.common.BookingPolicy;
import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.component.ScreeningCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * BKG-05 실시간 좌석 이벤트 (SSE) — 명세 5장.
 *
 * <p>Valkey {@code seat-events:{회차}}를 구독해 그 회차를 보고 있는 연결에만 보낸다. 구독은
 * <b>보는 사람이 있는 회차만</b> 걸고 마지막 연결이 끊기면 해제한다 — 파드마다 구독하므로
 * 어느 파드에서 발행해도 모든 화면이 받는다.
 *
 * <p>보내는 모양은 {@code retry: 2000} → {@code : connected} → 이벤트({@code id}=seq,
 * {@code event: seat}, {@code data}=`seat-event.v1`) → 15초마다 {@code : ping}이다.
 * 하트비트가 없으면 ALB 유휴 120초·CloudFront 원본 대기 30초에 연결이 끊긴다.
 *
 * <p>이벤트 본문은 발행된 JSON을 <b>그대로</b> 흘려보낸다(다시 만들지 않는다) — 발행자와
 * 화면 사이에 형식이 어긋날 자리를 없앤다.
 */
@Slf4j
@Service
public class SeatStreamService {

    private final ScreeningCache screeningCache;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    /** 회차 → 그 회차를 보고 있는 연결. 구독 추가·해제와 함께 바뀐다 */
    private final Map<Long, Screen> screens = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();

    public SeatStreamService(ScreeningCache screeningCache,
                             RedisMessageListenerContainer listenerContainer,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.screeningCache = screeningCache;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        Gauge.builder("booking_sse_connections", connections, AtomicInteger::get)
            .description("열려 있는 좌석 이벤트 SSE 연결 수")
            .register(meterRegistry);
    }

    /**
     * 연결을 연다. 회차가 없으면 <b>스트림을 열기 전에</b> 404로 답한다(명세 5장).
     * 타임아웃은 두지 않는다 — 하트비트로 살아 있음을 알리고, 끊는 쪽은 화면·배포다.
     */
    public SseEmitter subscribe(Long screeningId) {
        screeningCache.screening(screeningId);   // 없으면 404 SCREENING_NOT_FOUND

        SseEmitter emitter = new SseEmitter(0L);
        // 구독 등록·해제를 맵 변경과 같은 자리에서 한다 — 마지막 연결이 끊기는 순간에 새 연결이
        // 들어오면, 구독은 해제됐는데 맵에는 남아 이벤트를 못 받는 상태가 생긴다
        screens.compute(screeningId, (id, existing) -> {
            Screen screen = existing != null ? existing : new Screen(id);
            screen.add(emitter);
            return screen;
        });
        connections.incrementAndGet();

        emitter.onCompletion(() -> remove(screeningId, emitter));
        emitter.onTimeout(() -> remove(screeningId, emitter));
        emitter.onError(e -> remove(screeningId, emitter));

        try {
            emitter.send(SseEmitter.event().reconnectTime(BookingPolicy.SSE_RETRY_MILLIS).comment("connected"));
        } catch (IOException e) {
            // 열자마자 끊긴 연결. 정리만 하고 넘어간다
            remove(screeningId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 15초마다 주석 줄 하나 — 본문이 아니라 화면 로직에 영향이 없다 */
    @Scheduled(fixedRateString = "#{T(com.seatsac.booking.common.BookingPolicy).SSE_HEARTBEAT.toMillis()}")
    public void heartbeat() {
        screens.values().forEach(screen -> screen.broadcast(SseEmitter.event().comment("ping"), null));
    }

    /** 배포·종료 때 연결을 정상 종료한다 (SIGTERM → preStop 5초 → graceful 20초) */
    @PreDestroy
    public void closeAll() {
        log.info("SSE 연결 정상 종료 connections={}", connections.get());
        screens.values().forEach(Screen::closeAll);
        screens.clear();
        // 맵을 비운 뒤에는 onCompletion이 줄일 대상을 찾지 못한다 — 여기서 맞춘다
        connections.set(0);
    }

    public int connectionCount() {
        return connections.get();
    }

    private void remove(Long screeningId, SseEmitter emitter) {
        screens.compute(screeningId, (id, screen) -> {
            if (screen == null) {
                return null;
            }
            if (screen.remove(emitter)) {
                connections.decrementAndGet();
            }
            if (screen.isEmpty()) {
                screen.unsubscribe();   // 보는 사람이 없는 회차는 구독하지 않는다
                return null;
            }
            return screen;
        });
    }

    /** 한 회차의 구독과 연결들 */
    private final class Screen implements MessageListener {

        private final Long screeningId;
        private final ChannelTopic topic;
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

        private Screen(Long screeningId) {
            this.screeningId = screeningId;
            this.topic = new ChannelTopic(RedisKeys.seatEvents(screeningId));
            listenerContainer.addMessageListener(this, topic);
        }

        private void add(SseEmitter emitter) {
            emitters.add(emitter);
        }

        private boolean remove(SseEmitter emitter) {
            return emitters.remove(emitter);
        }

        private void unsubscribe() {
            listenerContainer.removeMessageListener(this, topic);
        }

        private boolean isEmpty() {
            return emitters.isEmpty();
        }

        @Override
        public void onMessage(org.springframework.data.redis.connection.Message message, byte[] pattern) {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            SseEmitter.SseEventBuilder event = SseEmitter.event().name("seat").data(payload);
            String seq = seqOf(payload);
            if (seq != null) {
                event = event.id(seq);
            }
            broadcast(event, payload);
        }

        /** 보내다 실패한 연결은 끊긴 것이다 — 완료 처리하면 {@code onCompletion}이 정리한다 */
        private void broadcast(SseEmitter.SseEventBuilder event, String payload) {
            for (SseEmitter emitter : emitters) {
                try {
                    synchronized (emitter) {
                        emitter.send(event);
                    }
                } catch (IOException | IllegalStateException e) {
                    log.debug("SSE 전송 실패 — 연결을 닫는다 screeningId={} cause={}",
                        screeningId, e.getClass().getSimpleName());
                    emitter.complete();
                }
            }
            if (payload != null && log.isTraceEnabled()) {
                log.trace("SSE 전달 screeningId={} connections={}", screeningId, emitters.size());
            }
        }

        private void closeAll() {
            unsubscribe();
            emitters.forEach(SseEmitter::complete);
            emitters.clear();
        }

        /** {@code id:}는 이벤트 seq다. 읽지 못하면 id 없이 보낸다(화면은 data의 seq를 본다) */
        private String seqOf(String payload) {
            try {
                JsonNode seq = objectMapper.readTree(payload).get("seq");
                return seq == null ? null : seq.asText();
            } catch (Exception e) {
                log.warn("좌석 이벤트 seq를 읽지 못했다 screeningId={}", screeningId, e);
                return null;
            }
        }
    }
}
