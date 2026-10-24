package com.seatsac.catalog.common;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.CommonResponseCode;
import com.seatsac.common.core.error.ErrorCode;
import com.seatsac.common.core.error.ErrorDetail;
import java.util.Arrays;
import java.util.List;

/**
 * catalog의 세분 오류 이름. enum 이름은 {@code details[].reason}·MDC {@code errorCode}에, 응답 {@code code}는
 * {@link #commonCode()}에서 나온다(공통 모듈 규칙). 던질 때는 {@code CatalogErrorCode.MOVIE_NOT_FOUND.exception()}.
 *
 * <p>관리자 API(CAT-06·LAY-07, P3)의 {@code SCHEDULE_CONFLICT}·{@code SEAT_SOLD}는 그 작업에서 추가한다.
 */
public enum CatalogErrorCode implements ErrorCode {

    // 404 NOT_FOUND
    MOVIE_NOT_FOUND(CommonResponseCode.NOT_FOUND, "영화를 찾을 수 없습니다."),
    SCREENING_NOT_FOUND(CommonResponseCode.NOT_FOUND, "회차를 찾을 수 없습니다."),
    AUDITORIUM_NOT_FOUND(CommonResponseCode.NOT_FOUND, "상영관을 찾을 수 없습니다."),
    LAYOUT_NOT_FOUND(CommonResponseCode.NOT_FOUND, "좌석 배치를 찾을 수 없습니다.");

    private final CommonResponseCode commonCode;
    private final String message;

    CatalogErrorCode(CommonResponseCode commonCode, String message) {
        this.commonCode = commonCode;
        this.message = message;
    }

    @Override
    public CommonResponseCode commonCode() {
        return commonCode;
    }

    @Override
    public String message() {
        return message;
    }

    public BusinessException exception() {
        return new BusinessException(this, message, List.of());
    }

    public BusinessException exception(ErrorDetail... details) {
        return new BusinessException(this, message, Arrays.asList(details));
    }
}
