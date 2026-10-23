package com.seatsac.booking.common;

import com.seatsac.common.core.error.BusinessException;
import java.util.List;

/**
 * Catalog가 회차·배치를 404로 답한 경우 — 가용성 문제가 아니라 <b>도메인 응답</b>이다.
 *
 * <p>별도 타입으로 둔 이유: 서킷({@code resilience4j.circuitbreaker.instances.catalog})의
 * {@code ignore-exceptions}가 이 타입만 집어낼 수 있어야 한다. {@code BusinessException} 전체를
 * 무시하면 {@code InternalRestClientFactory}가 타임아웃·5xx를 바꿔 주는 503까지 무시되어
 * <b>Catalog 장애에도 서킷이 열리지 않는다</b>.
 */
public class ScreeningNotFoundException extends BusinessException {

    public ScreeningNotFoundException() {
        super(BookingErrorCode.SCREENING_NOT_FOUND, BookingErrorCode.SCREENING_NOT_FOUND.message(),
            List.of());
    }
}
