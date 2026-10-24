package com.seatsac.catalog.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.catalog.dto.ItemsResponse;
import com.seatsac.catalog.dto.MovieStatus;
import com.seatsac.catalog.service.LayoutService;
import com.seatsac.catalog.service.MovieService;
import com.seatsac.catalog.service.ScreeningService;
import com.seatsac.catalog.service.TheaterService;
import com.seatsac.common.core.PageResponse;
import com.seatsac.common.web.CommonWebAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 웹 계층만(DB·Valkey 없음): 계약의 파라미터 제약(page≥0, 1≤size≤50, version≥1, ids 1~10, screeningIds 1~100)과
 * 기본값이 400 {@code VALIDATION_FAILED}·공통 오류 형식으로 나가는지. 비즈니스 흐름은 CatalogApiIntegrationTest.
 */
@WebMvcTest(controllers = {MovieController.class, TheaterController.class, ScreeningController.class,
    AuditoriumController.class, InternalCatalogController.class})
@Import(CommonWebAutoConfiguration.class)
class CatalogWebValidationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MovieService movieService;
    @MockitoBean
    TheaterService theaterService;
    @MockitoBean
    ScreeningService screeningService;
    @MockitoBean
    LayoutService layoutService;

    @Test
    void moviesUseContractDefaultsAndBounds() throws Exception {
        when(movieService.list(any(), anyInt(), anyInt())).thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mvc.perform(get("/movies")).andExpect(status().isOk());
        verify(movieService).list(MovieStatus.NOW_SHOWING, 0, 20);

        mvc.perform(get("/movies").param("size", "51"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details[0].field").value("size"));
        mvc.perform(get("/movies").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("page"));
        mvc.perform(get("/movies").param("status", "UNKNOWN"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].reason").value("INVALID_TYPE"));
        mvc.perform(get("/movies/abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("movieId"))
            .andExpect(jsonPath("$.details[0].reason").value("INVALID_TYPE"));
    }

    @Test
    void theatersDefaultToHasLayoutTrue() throws Exception {
        when(theaterService.list(eq(true), any())).thenReturn(new ItemsResponse<>(List.of()));

        mvc.perform(get("/theaters"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
        verify(theaterService).list(true, null);
    }

    @Test
    void screeningsRequireDateAndTheater() throws Exception {
        mvc.perform(get("/screenings").param("date", "2026-11-16"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("theaterId"))
            .andExpect(jsonPath("$.details[0].reason").value("REQUIRED"));
        mvc.perform(get("/screenings").param("date", "11/16/2026").param("theaterId", "1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("date"));
    }

    @Test
    void layoutVersionMustBePositive() throws Exception {
        mvc.perform(get("/auditoriums/10/layout").param("version", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("version"));
    }

    @Test
    void internalListsAreBounded() throws Exception {
        when(theaterService.byIds(any())).thenReturn(new ItemsResponse<>(List.of()));
        when(screeningService.summaries(any())).thenReturn(new ItemsResponse<>(List.of()));

        mvc.perform(get("/internal/theaters").param("ids", "1,2")).andExpect(status().isOk());
        mvc.perform(get("/internal/theaters").param("ids", "1,2,3,4,5,6,7,8,9,10,11"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("ids"));
        mvc.perform(get("/internal/theaters"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].reason").value("REQUIRED"));

        mvc.perform(post("/internal/screenings/summaries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningIds\":[1]}"))
            .andExpect(status().isOk());
        mvc.perform(post("/internal/screenings/summaries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"screeningIds\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("screeningIds"));
        mvc.perform(post("/internal/screenings/summaries").contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/screenings/summaries").contentType(MediaType.APPLICATION_JSON)
                .content("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].reason").value("MALFORMED_BODY"));
    }

    @Test
    void unknownPathIsNotFoundInErrorFormat() throws Exception {
        mvc.perform(get("/nope"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.details").isArray());
        verifyNoInteractions(movieService, theaterService, screeningService, layoutService);
    }
}
