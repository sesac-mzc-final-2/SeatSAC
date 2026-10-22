package com.seatsac.common.core.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 오류 상세 한 건 { field, value?, reason? } — contracts/schemas/error.v1.json details.items.
 * value에는 카드 번호·토큰·비밀번호를 넣지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(String field, Object value, String reason) {

    public ErrorDetail {
        if (field == null) {
            throw new IllegalArgumentException("field는 필수");
        }
    }

    public static ErrorDetail of(String field, String reason) {
        return new ErrorDetail(field, null, reason);
    }
}
