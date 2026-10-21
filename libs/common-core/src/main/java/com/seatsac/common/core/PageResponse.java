package com.seatsac.common.core;

import java.util.List;

/**
 * 페이징 응답 공통 형식 {@code {content, page, size, totalElements, totalPages}} — API 명세서 1-3.
 * BKG-14(예매 내역) 등 목록 API가 같은 모양으로 응답하도록 여기에 둔다.
 *
 * @param page 0부터 시작하는 페이지 번호
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public PageResponse {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size <= 0 ? 0 : (int) Math.ceilDiv(totalElements, (long) size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
