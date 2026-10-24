package com.seatsac.catalog.web;

import com.seatsac.catalog.dto.ItemsResponse;
import com.seatsac.catalog.dto.TheaterResponse;
import com.seatsac.catalog.service.TheaterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CAT-03 극장 목록 — 상영시간 화면의 극장 선택용. 기본은 배치도 있는 극장만 */
@RestController
@RequestMapping("/theaters")
public class TheaterController {

    private final TheaterService theaterService;

    public TheaterController(TheaterService theaterService) {
        this.theaterService = theaterService;
    }

    @GetMapping
    public ItemsResponse<TheaterResponse> listTheaters(
        @RequestParam(defaultValue = "true") boolean hasLayout,
        @RequestParam(required = false) String region) {
        return theaterService.list(hasLayout, region);
    }
}
