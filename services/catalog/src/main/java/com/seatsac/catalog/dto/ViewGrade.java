package com.seatsac.catalog.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** 관람 등급 — 계약 enum ["ALL","12","15","19"]. 시드 {@code view_grade_code} 0·12·15·19에서 바꾼다. */
public enum ViewGrade {
    ALL("ALL"), AGE_12("12"), AGE_15("15"), AGE_19("19");

    private final String json;

    ViewGrade(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    /** 모르는 코드는 보수적으로 19(청소년 관람 불가)로 둔다 — 등급을 낮춰 보여 주지 않는다 */
    public static ViewGrade fromCode(Integer code) {
        if (code == null) {
            return AGE_19;
        }
        return switch (code) {
            case 0 -> ALL;
            case 12 -> AGE_12;
            case 15 -> AGE_15;
            default -> AGE_19;
        };
    }
}
