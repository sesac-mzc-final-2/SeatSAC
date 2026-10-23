package com.seatsac.catalog.dto;

/** 좌석 종류 — 계약 SeatType. 롯데 PhysicalBlockCode 1 일반, 190 리클라이너, 그 외(40·290 등) 미확인 */
public enum SeatType {
    NORMAL, RECLINER, UNKNOWN;

    public static SeatType fromCode(Integer code) {
        if (code == null) {
            return UNKNOWN;
        }
        return switch (code) {
            case 1 -> NORMAL;
            case 190 -> RECLINER;
            default -> UNKNOWN;
        };
    }
}
