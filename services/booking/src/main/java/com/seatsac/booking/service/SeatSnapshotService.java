package com.seatsac.booking.service;

import com.seatsac.booking.common.RedisKeys;
import com.seatsac.booking.component.CatalogClient.Screening;
import com.seatsac.booking.component.ScreeningCache;
import com.seatsac.booking.component.ScreeningCache.SeatLayout;
import com.seatsac.booking.component.SeatHoldRedisRepository;
import com.seatsac.booking.dto.response.SeatStatusResponse;
import com.seatsac.booking.dto.response.SeatStatusResponse.SeatView;
import com.seatsac.booking.enums.BookingStatus;
import com.seatsac.booking.enums.SeatStatus;
import com.seatsac.common.core.time.Times;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * BKG-01 좌석 현황 스냅샷 — 명세 5장, `redis-usage.md` 5장. 트랜잭션 없음.
 *
 * <pre>
 * 1. GET seat-seq:{회차}   (반드시 먼저 — 화면이 빈틈을 판정하는 기준)
 * 2. 회차·좌석 ID 목록      파드 캐시 (없으면 Catalog)
 * 3. RDS                   booking_seat + booking: CONFIRMED → RESERVED, PENDING → HELD
 * 4. MGET seat:{회차}:*     값이 있으면 HELD
 * 5. 합쳐서 응답            우선순위 RESERVED > HELD > AVAILABLE
 * </pre>
 *
 * 나중에 스냅샷 캐시를 앞에 감쌀 수 있게 이 클래스 하나로 분리해 둔다(명세 5장).
 * RDS가 PENDING인데 Valkey 키가 이미 사라진 좌석은 만료 보정 전이라 HELD로 보여 준다.
 */
@Service
@RequiredArgsConstructor
public class SeatSnapshotService {

    private final Clock clock;
    private final StringRedisTemplate redis;
    private final ScreeningCache screeningCache;
    private final SeatHoldRedisRepository seatHoldRedis;
    private final BookingSeatStatusReader seatStatusReader;

    public SeatStatusResponse snapshot(Long screeningId) {
        String rawSeq = redis.opsForValue().get(RedisKeys.seatSeq(screeningId));
        long seq = rawSeq == null ? 0L : Long.parseLong(rawSeq);

        Screening screening = screeningCache.screening(screeningId);
        SeatLayout layout = screeningCache.layout(screening.auditoriumId(), screening.layoutVersion());
        List<Long> seatIds = layout.seatIds();

        Map<Long, SeatStatus> statuses = new HashMap<>(seatStatusReader.findSeatStatuses(screeningId));
        seatHoldRedis.findHeldSeats(screeningId, seatIds).keySet()
            .forEach(seatId -> statuses.merge(seatId, SeatStatus.HELD,
                (rds, redisHeld) -> rds == SeatStatus.RESERVED ? SeatStatus.RESERVED : SeatStatus.HELD));

        List<SeatView> seats = seatIds.stream()
            .map(seatId -> new SeatView(seatId, statuses.getOrDefault(seatId, SeatStatus.AVAILABLE)))
            .toList();

        return SeatStatusResponse.builder()
            .screeningId(screeningId)
            .layoutVersion(screening.layoutVersion())
            .seq(seq)
            .generatedAt(Times.toApi(clock.instant()))
            .seats(seats)
            .build();
    }

    /** RDS 좌석 상태 조회만 담당 (읽기 트랜잭션) */
    @Service
    @RequiredArgsConstructor
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public static class BookingSeatStatusReader {

        private final com.seatsac.booking.repository.BookingSeatRepository bookingSeatRepository;

        public Map<Long, SeatStatus> findSeatStatuses(Long screeningId) {
            Map<Long, SeatStatus> statuses = new HashMap<>();
            for (Object[] row : bookingSeatRepository.findSeatStatusesByScreeningId(screeningId)) {
                Long seatId = (Long) row[0];
                BookingStatus status = (BookingStatus) row[1];
                SeatStatus seatStatus = status == BookingStatus.CONFIRMED ? SeatStatus.RESERVED : SeatStatus.HELD;
                statuses.merge(seatId, seatStatus,
                    (a, b) -> a == SeatStatus.RESERVED || b == SeatStatus.RESERVED ? SeatStatus.RESERVED : a);
            }
            return statuses;
        }
    }
}
