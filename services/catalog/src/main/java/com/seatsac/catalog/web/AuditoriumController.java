package com.seatsac.catalog.web;

import com.seatsac.catalog.dto.AuditoriumLayoutResponse;
import com.seatsac.catalog.service.LayoutService;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CAT-04 배치도 — {@code /auditoriums/{auditoriumId}/layout?version}. 좌석 점유 상태는 BKG-01 */
@RestController
@RequestMapping("/auditoriums")
public class AuditoriumController {

    private final LayoutService layoutService;

    public AuditoriumController(LayoutService layoutService) {
        this.layoutService = layoutService;
    }

    @GetMapping("/{auditoriumId}/layout")
    public AuditoriumLayoutResponse getAuditoriumLayout(
        @PathVariable Long auditoriumId,
        @RequestParam(required = false) @Min(1) Integer version) {
        return layoutService.get(auditoriumId, version);
    }
}
