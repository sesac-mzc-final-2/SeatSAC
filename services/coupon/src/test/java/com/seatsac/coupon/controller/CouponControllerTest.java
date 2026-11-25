package com.seatsac.coupon.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.common.test.WithCurrentUser;
import com.seatsac.common.web.CommonWebAutoConfiguration;
import com.seatsac.coupon.common.CouponErrorCode;
import com.seatsac.coupon.dto.CouponIssueResponse;
import com.seatsac.coupon.dto.MyCouponListResponse;
import com.seatsac.coupon.entity.CouponIssueState;
import com.seatsac.coupon.service.CouponClaimService;
import com.seatsac.coupon.service.CouponQueryService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 외부 API의 HTTP 층 — 상태 코드·오류 본문({@code code}가 계약의 COUPON_* 그대로)·헤더 전달. 흐름은 FlowTest가 본다 */
@WebMvcTest(controllers = CouponController.class)
@ImportAutoConfiguration(CommonWebAutoConfiguration.class)
class CouponControllerTest {

    /** 멱등 키 — 실행마다 새 UUID(리터럴로 두면 gitleaks generic-api-key 오탐) */
    private static final String KEY = java.util.UUID.randomUUID().toString();
    private static final String ISSUE_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CouponClaimService claims;
    @MockitoBean
    CouponQueryService queries;

    @Test
    void claimReturns201WithIssueAndPassesGradeAndKey() throws Exception {
        given(claims.claim(WithCurrentUser.DEFAULT_USER, "VIP", 3L, KEY)).willReturn(new CouponIssueResponse(
            ISSUE_ID, 3L, "VIP 선착순 50% 할인", 50, CouponIssueState.ISSUED,
            OffsetDateTime.parse("2026-11-27T15:00:00+09:00")));

        mvc.perform(post("/coupons/events/3/claim").with(WithCurrentUser.user())
                .with(WithCurrentUser.idempotencyKey(KEY)).header("X-User-Grade", "VIP"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.couponIssueId").value(ISSUE_ID))
            .andExpect(jsonPath("$.state").value("ISSUED"))
            .andExpect(jsonPath("$.expiresAt").value("2026-11-27T15:00:00+09:00"));
    }

    @Test
    void notEligibleUsesCouponCodeAsResponseCode() throws Exception {
        given(claims.claim(any(), isNull(), anyLong(), any())).willThrow(
            CouponErrorCode.COUPON_NOT_ELIGIBLE.exception(ErrorDetail.of("grade", "NORMAL")));

        mvc.perform(post("/coupons/events/3/claim").with(WithCurrentUser.user()).with(WithCurrentUser.idempotencyKey(KEY)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("COUPON_NOT_ELIGIBLE"))
            .andExpect(jsonPath("$.details[0].field").value("grade"))
            .andExpect(jsonPath("$.details[0].value").value("NORMAL"))
            .andExpect(jsonPath("$.traceId").value(WithCurrentUser.DEFAULT_TRACE));
    }

    @Test
    void soldOutIs409WithReasonOnlyDetail() throws Exception {
        given(claims.claim(any(), any(), eq(3L), any())).willThrow(CouponErrorCode.COUPON_SOLD_OUT.exception());

        mvc.perform(post("/coupons/events/3/claim").with(WithCurrentUser.user()).with(WithCurrentUser.idempotencyKey(KEY))
                .header("X-User-Grade", "VIP"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.details[0].reason").value("COUPON_SOLD_OUT"));   // 공통 규칙: details는 세분 이름 하나 이상
    }

    @Test
    void idempotencyConflictKeepsCommonCode() throws Exception {
        given(claims.claim(any(), any(), anyLong(), any())).willThrow(CouponErrorCode.IDEMPOTENCY_KEY_CONFLICT.exception());

        mvc.perform(post("/coupons/events/3/claim").with(WithCurrentUser.user()).with(WithCurrentUser.idempotencyKey(KEY)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
            .andExpect(jsonPath("$.details[0].reason").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void missingUserHeaderIs401AndNeverReachesService() throws Exception {
        mvc.perform(post("/coupons/events/3/claim").with(WithCurrentUser.idempotencyKey(KEY)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(claims);
    }

    @Test
    void missingKeyIsValidationFailedFromService() throws Exception {
        given(claims.claim(any(), any(), anyLong(), isNull())).willThrow(new BusinessException(
            CommonResponseCode.VALIDATION_FAILED, List.of(ErrorDetail.required("Idempotency-Key"))));

        mvc.perform(post("/coupons/events/3/claim").with(WithCurrentUser.user()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("Idempotency-Key"));
    }

    @Test
    void myCouponsIsItemsList() throws Exception {
        given(queries.mine(WithCurrentUser.DEFAULT_USER)).willReturn(new MyCouponListResponse(List.of()));

        mvc.perform(get("/coupons/me").with(WithCurrentUser.user()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void noCurrentEventIs404() throws Exception {
        given(queries.current(WithCurrentUser.DEFAULT_USER)).willThrow(CouponErrorCode.NO_CURRENT_EVENT.exception());

        mvc.perform(get("/coupons/events/current").with(WithCurrentUser.user()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
