package com.seatsac.catalog.web;

import com.seatsac.catalog.dto.ItemsResponse;
import com.seatsac.catalog.dto.ScreeningResponse;
import com.seatsac.catalog.service.ScreeningService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAT-03 회차 — {@code /screenings}. {@code /screenings/{id}/seats}·{@code /seats/stream}은 booking 소관이고
 * Gateway가 먼저 booking으로 보낸다(GW-01 라우트 순서).
 */
@RestController
@RequestMapping("/screenings")
public class ScreeningController {

    private final ScreeningService screeningService;

    public ScreeningController(ScreeningService screeningService) {
        this.screeningService = screeningService;
    }

    @GetMapping
    public ItemsResponse<ScreeningResponse> listScreenings(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam Long theaterId,
        @RequestParam(required = false) Long movieId) {
        return screeningService.list(date, theaterId, movieId);
    }

    @GetMapping("/{screeningId}")
    public ScreeningResponse getScreening(@PathVariable Long screeningId) {
        return screeningService.get(screeningId);
    }
}
