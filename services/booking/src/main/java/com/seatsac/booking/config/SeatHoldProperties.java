package com.seatsac.booking.config;

import com.seatsac.booking.common.BookingPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 선점 설정 {@code app.seat-hold.*} — 환경변수 {@code APP_SEAT_HOLD_TTL_SECONDS}
 * (docs/service-runtime-contract.md 3장, 기본 300초).
 *
 * <p>선점 TTL은 예매 {@code expires_at = now + TTL}과 Valkey 좌석·{@code user-hold} 키의 EX에 같이 쓰인다.
 * 값이 서로 다르면 「키는 살아 있는데 예매는 만료」 같은 어긋남이 생기므로 두 곳 모두 이 값만 읽는다.
 * 처리 중 멱등 키의 TTL은 {@link #idempotencyInProgressTtl()}(이 값과 300초 중 긴 쪽)이다.
 *
 * <p>범위 30~600초. 30초 미만이면 고아 키 유예({@link BookingPolicy#HOLD_COMMIT_GRACE} 10초)와 RDS 만료 스캔
 * 주기(10초)보다 선점이 먼저 끝나 정상 선점을 고아로 지울 수 있다. 600초 초과는 좌석을 너무 오래 묶는다.
 * 범위를 벗어나면 기동이 실패한다(잘못된 ConfigMap을 조용히 쓰지 않게).
 */
@Validated
@ConfigurationProperties("app.seat-hold")
public record SeatHoldProperties(@Min(30) @Max(600) Integer ttlSeconds) {

    public SeatHoldProperties {
        if (ttlSeconds == null) {
            ttlSeconds = BookingPolicy.DEFAULT_HOLD_TTL_SECONDS;
        }
    }

    /** 선점 TTL */
    public Duration holdTtl() {
        return Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 처리 중({@code IN_PROGRESS}) 멱등 키의 TTL = 선점 TTL과 기본 선점 TTL(300초) 중 <b>긴 쪽</b>.
     *
     * <p>선점 TTL보다 짧으면 안 된다 — 끝나기 전에 좌석이 풀려야 같은 키의 재시도가 앞 요청과 좌석을 다투지 않는다.
     * 그렇다고 데모처럼 선점 TTL을 짧게(예: 30초) 줄였을 때 같이 줄이면, 오래 걸리는 첫 요청이 끝나기 전에
     * 처리권이 풀려 재시도가 409를 먼저 저장하고 첫 요청의 201은 처리권이 달라 저장되지 않는다(재시도가 첫
     * 결과 대신 409를 재생). 그래서 300초 아래로는 내리지 않는다.
     */
    public Duration idempotencyInProgressTtl() {
        Duration hold = holdTtl();
        Duration floor = Duration.ofSeconds(BookingPolicy.DEFAULT_HOLD_TTL_SECONDS);
        return hold.compareTo(floor) > 0 ? hold : floor;
    }
}
