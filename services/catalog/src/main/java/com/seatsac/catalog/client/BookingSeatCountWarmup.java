package com.seatsac.catalog.client;

import com.seatsac.common.core.trace.TraceIds;
import com.seatsac.common.core.trace.TraceKeys;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 기동 직후 booking seat-counts를 <b>한 번</b> 불러 첫 호출 비용(클래스 로딩·JIT·Jackson으로 추정)을 미리 치른다.
 *
 * <p>JVM의 첫 booking 호출은 CI 러너에서 300ms 예산(CAT-03)을 넘겼고(PR #43), 같은 비용은 운영 catalog 파드에도
 * 있어 롤링(maxSurge 1)·HPA로 파드가 새로 뜰 때마다 첫 {@code /screenings} 응답이 {@code remainingSeatsSource: TOTAL}로
 * 떨어지고 {@code catalog_seat_counts_fallback_total}이 배포마다 1 올랐다. 예열하면 첫 실제 요청부터 BOOKING이다.
 *
 * <ul>
 *   <li>{@link ApplicationReadyEvent}에서 <b>별도 데몬 스레드</b>로 돈다 — readiness({@code ACCEPTING_TRAFFIC})는
 *       이 이벤트의 리스너가 모두 끝난 뒤 바뀌므로, 리스너 스레드에서 호출하면 그만큼 readiness가 늦어진다.
 *       호출은 연결 1초·응답 300ms 타임아웃에 묶여 길어야 1~2초 안에 끝난다</li>
 *   <li>{@link BookingSeatCountClient#fetch}를 서킷 {@code booking} 밖에서 직접 부른다 — 실패해도 서킷 실패율과
 *       대체 카운터에 들어가지 않는다(booking이 아직 안 떠 있어도 서킷이 열리거나 경보 수치가 움직이면 안 된다)</li>
 *   <li>실패는 WARN 로그뿐, 기동·readiness를 막지 않는다</li>
 *   <li>회차 ID는 {@value #WARMUP_SCREENING_ID} 하나 — booking 내부 계약상 요청한 회차는 예매가 없어도 0으로 돌아오므로
 *       존재하지 않는 회차여도 200이다(ID는 양수여야 한다)</li>
 * </ul>
 */
@Component
public class BookingSeatCountWarmup {

    static final long WARMUP_SCREENING_ID = 1L;
    static final String THREAD_NAME = "booking-warmup";
    static final String JOB_NAME = "booking-seat-counts-warmup";

    private static final Logger log = LoggerFactory.getLogger(BookingSeatCountWarmup.class);

    private final BookingSeatCountClient client;

    public BookingSeatCountWarmup(BookingSeatCountClient client) {
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread thread = new Thread(this::warmUp, THREAD_NAME);
        thread.setDaemon(true);   // 종료 중이면 기다리지 않는다
        thread.start();
    }

    /**
     * 예열 1회. 요청 밖 작업이라 traceId를 새로 만들어 로그에 남긴다(규약 6장). 예외는 모두 삼킨다.
     */
    void warmUp() {
        MDC.put(TraceKeys.MDC_TRACE_ID, TraceIds.newId());
        MDC.put(TraceKeys.MDC_JOB, JOB_NAME);
        long started = System.nanoTime();
        try {
            client.fetch(List.of(WARMUP_SCREENING_ID));
            log.info("booking seat-counts warm-up done elapsedMs={}", elapsedMs(started));
        } catch (RuntimeException e) {
            // 타임아웃·연결 거부·5xx(공통 팩토리가 503 BusinessException으로 바꿈)·본문 해석 실패 — 기동은 계속한다
            log.warn("booking seat-counts warm-up failed elapsedMs={} cause={}: {}", elapsedMs(started),
                e.getClass().getSimpleName(), e.getMessage());
        } finally {
            MDC.clear();
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
