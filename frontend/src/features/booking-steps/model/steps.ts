/** 예매 흐름 단계 (W3 상영시간 → W4 좌석 선택 → W5 결제) */
export const BOOKING_STEPS = ["상영시간", "좌석 선택", "결제"] as const;

export type BookingStep = 0 | 1 | 2;
