package com.seatsac.catalog.dto.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** POST /internal/screenings/summaries 본문. 1~100개(계약) */
public record ScreeningSummariesRequest(
    @NotNull @Size(min = 1, max = 100) List<@NotNull Long> screeningIds
) {
}
