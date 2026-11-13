package com.seatsac.payment.portone;

import com.seatsac.common.core.error.BusinessException;
import com.seatsac.common.core.error.ErrorDetail;
import com.seatsac.payment.common.PaymentErrorCode;
import java.util.List;

/**
 * 포트원이 응답하지 못했다(타임아웃·연결 오류·5xx·설정 오류 4xx) — 503 {@code SERVICE_UNAVAILABLE}
 * reason {@code PORTONE_UNAVAILABLE}. 서킷 {@code portone}은 이 예외만 실패로 센다.
 */
public class PortOneUnavailableException extends BusinessException {

    public PortOneUnavailableException(String detail) {
        super(PaymentErrorCode.PORTONE_UNAVAILABLE, PaymentErrorCode.PORTONE_UNAVAILABLE.message(),
            List.of(ErrorDetail.reason(PaymentErrorCode.PORTONE_UNAVAILABLE.name()),
                ErrorDetail.of("cause", detail)));
    }
}
