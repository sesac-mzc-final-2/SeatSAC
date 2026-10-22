package com.seatsac.common.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceIdsTest {

    @Test
    void newIdIsThirtyTwoLowercaseHex() {
        String id = TraceIds.newId();
        assertEquals(32, id.length());
        assertTrue(TraceIds.isValid(id), id);
    }

    @Test
    void rejectsWrongFormats() {
        assertFalse(TraceIds.isValid(null));
        assertFalse(TraceIds.isValid(""));
        assertFalse(TraceIds.isValid("4bf92f3577b34da6"));                      // 16자리 (이전 구현)
        assertFalse(TraceIds.isValid(UUID.randomUUID().toString()));            // 하이픈 36자
        assertFalse(TraceIds.isValid("4BF92F3577B34DA6A3CE929D0E0E4736"));      // 대문자
        assertFalse(TraceIds.isValid("4bf92f3577b34da6a3ce929d0e0e473g"));      // hex 아님
    }
}
