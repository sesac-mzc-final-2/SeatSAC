package com.seatsac.catalog.web;

import com.seatsac.catalog.dto.MovieDetail;
import com.seatsac.catalog.dto.MovieStatus;
import com.seatsac.catalog.dto.MovieSummary;
import com.seatsac.catalog.service.MovieService;
import com.seatsac.common.core.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CAT-01·02 — contracts/openapi/catalog.openapi.yaml {@code /movies}. 메서드 이름 = operationId */
@RestController
@RequestMapping("/movies")
public class MovieController {

    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping
    public PageResponse<MovieSummary> listMovies(
        @RequestParam(defaultValue = "NOW_SHOWING") MovieStatus status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return movieService.list(status, page, size);
    }

    @GetMapping("/{movieId}")
    public MovieDetail getMovie(@PathVariable Long movieId) {
        return movieService.get(movieId);
    }
}
