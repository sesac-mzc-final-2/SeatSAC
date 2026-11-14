package com.seatsac.payment.enums;

/**
 * 결제 대행사 (ADR-112).
 *
 * <ul>
 *   <li>{@link #MOCK} — 모의 승인(ADR-13). 테스트·분석 전용, 호출자 제한은 booking이 한다</li>
 *   <li>{@link #PORTONE} — 포트원 V2(토스페이). 승인은 브라우저 결제창에서 일어나고 payment는 단건 조회로 검증한다</li>
 * </ul>
 */
public enum PgProvider {
    MOCK,
    PORTONE
}
