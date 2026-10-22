package com.seatsac.common.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraceIdsTest {

    @Test
    void newIdIs32LowerHex() {
        assertTrue(TraceIds.newId().matches("^[0-9a-f]{32}$"));
    }

    @Test
    void invalidIdsAreReplaced() {
        assertFalse(TraceIds.isValid(null));
        assertFalse(TraceIds.isValid("0123456789abcdef"));                    // 16자리(이전 형식)
        assertFalse(TraceIds.isValid("0123456789ABCDEF0123456789ABCDEF"));    // 대문자
        assertTrue(TraceIds.isValid(TraceIds.orNew("not-a-trace-id")));
    }

    @Test
    void validIdIsKept() {
        String id = "0123456789abcdef0123456789abcdef";
        assertEquals(id, TraceIds.orNew(id));
    }
}
