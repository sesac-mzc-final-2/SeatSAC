package com.seatsac.booking.checkout;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 결제 재확인 간격(D21). 결제창 미완료({@code NOT_YET})는 사용자가 아직 결제 중일 수 있어 5초 그대로,
 * 포트원·payment 무응답은 5 → 10 → 20 → 40초 …로 2배씩. 매번 ±20% 흔들림 — 같은 시각에 시작한 요청들이 계속 같이
 * 몰리지 않게(측정 3: payment 60초 정지 · 1000명). 선점 만료({@code expires_at})를 넘지 않는다 — 그 뒤는 payment 몫.
 */
@Component
public class CheckoutIntervals {

    private static final long BASE_MILLIS = CheckoutProperties.VERIFY_INTERVAL.toMillis();
    private static final int MAX_DOUBLINGS = 6;   // 5초 × 2^6 = 320초 > 선점 5분 — 더 늘릴 일이 없다

    /**
     * @param attempt    지금까지 보낸 확인 명령 수(1부터)
     * @param lastReason 마지막 결과 불명 사유. null이면 payment 무응답(결과가 안 왔다)
     */
    public Instant nextVerify(int attempt, String lastReason, Instant expiresAt, Instant now) {
        long base = "NOT_YET".equals(lastReason)
            ? BASE_MILLIS
            : BASE_MILLIS << Math.min(Math.max(attempt - 1, 0), MAX_DOUBLINGS);
        long jitter = (long) (base * 0.2);
        long millis = base + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitter, jitter + 1));
        Instant next = now.plus(Duration.ofMillis(Math.max(millis, 1)));
        return next.isAfter(expiresAt) ? expiresAt : next;
    }
}
