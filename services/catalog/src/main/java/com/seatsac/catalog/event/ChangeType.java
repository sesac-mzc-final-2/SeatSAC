package com.seatsac.catalog.event;

/**
 * 변경 이벤트를 내는 원본 종류. 장부 {@code change_ledger.aggregate_type}·outbox {@code event_type}·payload ID 필드 이름을 함께 정한다
 * (계약 {@code contracts/events/screening.changed.v1.json}·{@code movie.changed.v1.json}).
 */
public enum ChangeType {
    SCREENING("screening.changed", "screeningId"),
    MOVIE("movie.changed", "movieId");

    private final String eventType;
    private final String idField;

    ChangeType(String eventType, String idField) {
        this.eventType = eventType;
        this.idField = idField;
    }

    public String eventType() {
        return eventType;
    }

    /** payload에서 원본 ID를 담는 필드 이름 */
    public String idField() {
        return idField;
    }
}
