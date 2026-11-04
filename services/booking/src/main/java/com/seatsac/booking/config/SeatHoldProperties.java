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
 * <p>선점 TTL 하나가 세 곳에 같이 쓰인다: 예매 {@code expires_at = now + TTL}, Valkey 좌석·{@code user-hold}
 * 키의 EX, 처리 중 멱등 키의 TTL. 값이 서로 다르면 「키는 살아 있는데 예매는 만료」 같은 어긋남이 생기므로
 * 이 한 값만 읽는다.
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
}
