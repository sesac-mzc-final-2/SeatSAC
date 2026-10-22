package com.seatsac.booking.component;

import com.seatsac.booking.common.BookingErrorCode;
import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.web.client.InternalRestClientFactory;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Catalog 내부 호출 — 명세 8장. <b>단순 외부 호출만</b> 한다(캐시는 {@link ScreeningCache}).
 *
 * <p>연결 1초·응답 3초는 {@link InternalRestClientFactory} 기본값이고, 서킷은 이름 붙인
 * 인스턴스 {@code catalog}(실패율 50%·10초 열림)로 여기서 건다 — 호출별 폴백이 다르기 때문이다.
 * 팩토리가 타임아웃·5xx를 503으로 바꿔 던지므로 여기서는 세분 이름만 붙여 준다.
 */
@Component
public class CatalogClient {

    private final RestClient catalog;

    public CatalogClient(InternalRestClientFactory factory,
                         @Value("${app.services.catalog-url}") String catalogUrl) {
        this.catalog = factory.create(catalogUrl);
    }

    /**
     * 회차 정보 (상영 시작 확인·배치 버전·알림 본문). 없으면 404 → {@code SCREENING_NOT_FOUND}
     *
     * <p>404만 도메인 응답으로 바꾼다. 400·401·403은 우리 요청이 잘못된 것이라 그대로 올려
     * 500으로 드러나게 한다 — 「회차 없음」으로 덮으면 원인을 못 찾는다.
     */
    @CircuitBreaker(name = "catalog")
    public Screening findScreening(Long screeningId) {
        return catalog.get()
            .uri("/internal/screenings/{screeningId}", screeningId)
            .retrieve()
            .onStatus(CatalogClient::isNotFound, (req, res) -> {
                throw BookingErrorCode.SCREENING_NOT_FOUND.exception();
            })
            .body(Screening.class);
    }

    /** CAT-04 좌석 배치. 좌석 ID·번호·활성만 쓴다 (명세 12.3) */
    @CircuitBreaker(name = "catalog")
    public AuditoriumLayout findLayout(Long auditoriumId, Integer layoutVersion) {
        return catalog.get()
            .uri("/auditoriums/{auditoriumId}/layout?version={version}", auditoriumId, layoutVersion)
            .retrieve()
            .onStatus(CatalogClient::isNotFound, (req, res) -> {
                throw BookingErrorCode.SCREENING_NOT_FOUND.exception();
            })
            .body(AuditoriumLayout.class);
    }

    private static boolean isNotFound(HttpStatusCode status) {
        return status.value() == HttpStatus.NOT_FOUND.value();
    }

    /**
     * 서킷이 열렸거나 응답을 못 받은 경우 — 503 {@code CATALOG_UNAVAILABLE}.
     *
     * <p>4xx는 가용성 문제가 아니라 그대로 올린다 — 404는 도메인 응답(`SCREENING_NOT_FOUND`),
     * 나머지 4xx는 우리 쪽 요청 결함이라 500으로 드러나야 원인이 로그에 남는다.
     */
    public static RuntimeException unavailable(RuntimeException cause) {
        if (cause instanceof BusinessException business
            && business.code().responseCode().equals(CommonResponseCode.NOT_FOUND.name())) {
            return business;
        }
        if (cause instanceof HttpClientErrorException) {
            return cause;
        }
        return BookingErrorCode.CATALOG_UNAVAILABLE.exception();
    }

    /** 회차 — 쓰는 필드만 받는다(모르는 필드는 무시) */
    public record Screening(
        Long screeningId,
        Long auditoriumId,
        Integer layoutVersion,
        Instant startsAt,
        Instant endsAt,
        String movieTitle,
        String theaterName,
        String auditoriumName
    ) {
        /** 상영이 이미 시작됐는지 — 선점·취소 조건 (명세 5장) */
        public boolean hasStarted(Instant now) {
            return startsAt != null && !startsAt.isAfter(now);
        }
    }

    public record AuditoriumLayout(Long auditoriumId, Integer layoutVersion, List<LayoutSeat> seats) {
        public record LayoutSeat(Long seatId, String seatLabel, Boolean active) {
        }
    }
}
