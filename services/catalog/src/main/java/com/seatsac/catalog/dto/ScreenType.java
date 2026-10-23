package com.seatsac.catalog.dto;

/** 상영관 종류 — 계약 ScreenType. 롯데 {@code screen_type_code} 100 일반, 986 리클라이너. 그 외는 NORMAL */
public enum ScreenType {
    NORMAL, RECLINER;

    public static ScreenType fromCode(Integer code) {
        return code != null && code == 986 ? RECLINER : NORMAL;
    }
}
