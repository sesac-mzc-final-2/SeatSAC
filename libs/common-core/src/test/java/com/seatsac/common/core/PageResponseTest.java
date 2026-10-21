package com.seatsac.common.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    void totalPagesRoundsUp() {
        assertEquals(3, PageResponse.of(List.of("a"), 0, 10, 21).totalPages());
        assertEquals(2, PageResponse.of(List.of("a"), 0, 10, 20).totalPages());
        assertEquals(0, PageResponse.of(List.of(), 0, 10, 0).totalPages());
    }

    @Test
    void contentIsNeverNull() {
        assertEquals(List.of(), new PageResponse<String>(null, 0, 10, 0, 0).content());
    }
}
