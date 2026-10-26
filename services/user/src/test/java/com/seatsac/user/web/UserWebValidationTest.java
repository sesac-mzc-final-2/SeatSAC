package com.seatsac.user.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.common.core.PageResponse;
import com.seatsac.common.test.WithCurrentUser;
import com.seatsac.common.web.CommonWebAutoConfiguration;
import com.seatsac.user.dto.BookingHistoryStatus;
import com.seatsac.user.dto.PreferencesDto;
import com.seatsac.user.service.BookingHistoryService;
import com.seatsac.user.service.PreferenceService;
import com.seatsac.user.service.ProfileService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 웹 계층만(DB·다른 서비스 없음): 사용자 헤더 없으면 401, 계약의 형식 제약(name 1~20, 극장 ≤3, rank 1~3,
 * page≥0, 1≤size≤50, status enum)이 400 {@code VALIDATION_FAILED}·공통 오류 형식으로 나가는지.
 * 비즈니스 흐름은 UserApiIntegrationTest.
 */
@WebMvcTest(controllers = {ProfileController.class, MyBookingController.class, PreferenceController.class})
@Import(CommonWebAutoConfiguration.class)
class UserWebValidationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ProfileService profileService;
    @MockitoBean
    BookingHistoryService bookingHistoryService;
    @MockitoBean
    PreferenceService preferenceService;

    @Test
    void everyEndpointRequiresGatewayUserHeader() throws Exception {
        mvc.perform(get("/users/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.details[0].field").value("X-User-Id"))
            .andExpect(jsonPath("$.details[0].reason").value("USER_HEADER_MISSING"));
        mvc.perform(get("/users/me/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").header("X-User-Id", "not-a-uuid"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.details[0].reason").value("USER_HEADER_INVALID"));
        verifyNoInteractions(profileService, bookingHistoryService, preferenceService);
    }

    @Test
    void profileNameMustBeOneToTwentyChars() throws Exception {
        mvc.perform(patch("/users/me").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details[0].field").value("name"));
        mvc.perform(patch("/users/me").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + "가".repeat(21) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("name"));
        mvc.perform(patch("/users/me").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("name"));
        mvc.perform(patch("/users/me").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].reason").value("MALFORMED_BODY"));
        verifyNoInteractions(profileService);
    }

    @Test
    void bookingsUseContractDefaultsAndBounds() throws Exception {
        when(bookingHistoryService.listMyBookings(any(), any(), anyInt(), anyInt()))
            .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").value(0));
        verify(bookingHistoryService).listMyBookings(eq(WithCurrentUser.DEFAULT_USER), isNull(), eq(0), eq(20));

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()).param("status", "CANCELED")
                .param("page", "2").param("size", "50"))
            .andExpect(status().isOk());
        verify(bookingHistoryService).listMyBookings(WithCurrentUser.DEFAULT_USER, BookingHistoryStatus.CANCELED, 2, 50);

        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()).param("size", "51"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("size"));
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()).param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("page"));
        // PENDING은 내역 상태가 아니다 (계약 enum 밖)
        mvc.perform(get("/users/me/bookings").with(WithCurrentUser.user()).param("status", "PENDING"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("status"))
            .andExpect(jsonPath("$.details[0].reason").value("INVALID_TYPE"));
    }

    @Test
    void preferencesEnforceShapeConstraints() throws Exception {
        when(preferenceService.replaceMyPreferences(any(), any(), any())).thenReturn(PreferencesDto.empty());

        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[],\"preferredTimeSlot\":null,\"preferredGenres\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredTheaters").isArray())
            .andExpect(jsonPath("$.preferredTimeSlot").isEmpty())
            .andExpect(jsonPath("$.preferredGenres").isArray());

        // 극장 4곳
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[{\"rank\":1,\"theaterId\":1},{\"rank\":2,\"theaterId\":2},"
                    + "{\"rank\":3,\"theaterId\":3},{\"rank\":1,\"theaterId\":4}],\"preferredGenres\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("preferredTheaters"));
        // rank 0
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[{\"rank\":0,\"theaterId\":1}],\"preferredGenres\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("preferredTheaters[0].rank"));
        // theaterId 없음
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[{\"rank\":1}],\"preferredGenres\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].field").value("preferredTheaters[0].theaterId"));
        // 필수 배열 누락
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTimeSlot\":\"EVENING\"}"))
            .andExpect(status().isBadRequest());
        // 모르는 시간대 값
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[],\"preferredTimeSlot\":\"DAWN\",\"preferredGenres\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.details[0].reason").value("MALFORMED_BODY"));
        // 빈 장르 문자열
        mvc.perform(put("/users/me/preferences").with(WithCurrentUser.user()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"preferredTheaters\":[],\"preferredGenres\":[\" \"]}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPathIsNotFoundInErrorFormat() throws Exception {
        mvc.perform(get("/users/someone-else").with(WithCurrentUser.user()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.details").isArray());
    }
}
