package com.seatsac.catalog.web;

import com.seatsac.catalog.dto.ItemsResponse;
import com.seatsac.catalog.dto.internal.ScreeningForBookingResponse;
import com.seatsac.catalog.dto.internal.ScreeningSummariesRequest;
import com.seatsac.catalog.dto.internal.ScreeningSummary;
import com.seatsac.catalog.dto.internal.TheaterRef;
import com.seatsac.catalog.service.ScreeningService;
import com.seatsac.catalog.service.TheaterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 API — contracts/openapi/internal/catalog.internal.openapi.yaml. Gateway가 라우팅하지 않고(404)
 * 호출자는 NetworkPolicy로 booking·user만 허용된다. 사용자 인증 없음.
 */
@RestController
@RequestMapping("/internal")
public class InternalCatalogController {

    private final ScreeningService screeningService;
    private final TheaterService theaterService;

    public InternalCatalogController(ScreeningService screeningService, TheaterService theaterService) {
        this.screeningService = screeningService;
        this.theaterService = theaterService;
    }

    /** 호출자 booking — BKG-02·06·07 */
    @GetMapping("/screenings/{screeningId}")
    public ScreeningForBookingResponse getScreeningForBooking(@PathVariable Long screeningId) {
        return screeningService.forBooking(screeningId);
    }

    /** 호출자 user — USR-04. 1~100개 */
    @PostMapping("/screenings/summaries")
    public ItemsResponse<ScreeningSummary> getScreeningSummaries(@Valid @RequestBody ScreeningSummariesRequest request) {
        return screeningService.summaries(request.screeningIds());
    }

    /** 호출자 user — USR-05 선호 극장 검증. 쉼표 구분 최대 10개 */
    @GetMapping("/theaters")
    public ItemsResponse<TheaterRef> getTheatersByIds(@RequestParam @Size(min = 1, max = 10) List<Long> ids) {
        return theaterService.byIds(ids);
    }
}
