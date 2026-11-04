/** contracts/openapi/booking.openapi.yaml — BKG-01·02·03·05·10 */

export type SeatStatus = "AVAILABLE" | "HELD" | "RESERVED";

export type BookingStatus = "PENDING" | "CONFIRMED" | "PAYMENT_FAILED" | "EXPIRED" | "CANCELED";

export type PaymentStatus = "PROCESSING" | "APPROVED" | "FAILED" | "CANCELED";

export type FailureCode = "CARD_DECLINED" | "INSUFFICIENT_BALANCE" | null;

/** 좌석 이벤트 종류 (contracts/events/seat-event.v1.json) */
export type SeatEventType = "HELD" | "RELEASED" | "CONFIRMED" | "EXPIRED";

export interface SeatEvent {
  /** 회차별 단조 증가. 화면은 `seq == lastSeq + 1`일 때만 적용한다 */
  seq: number;
  type: SeatEventType;
  screeningId: number;
  seatIds: number[];
  /** ISO-8601 +09:00 */
  occurredAt: string;
  traceId: string;
}

export interface SeatSnapshot {
  screeningId: number;
  layoutVersion: number;
  /** 이 스냅샷을 만들기 직전의 이벤트 번호 */
  seq: number;
  generatedAt: string;
  seats: { seatId: number; status: SeatStatus }[];
}

/** BKG-02 선점 결과 — PENDING 예매가 함께 생긴다 */
export interface HoldResult {
  bookingId: number;
  screeningId: number;
  status: BookingStatus;
  seatIds: number[];
  seatLabels: string[];
  seatCount: number;
  /** 원. seatCount × 15,000 */
  amount: number;
  /** 선점 만료 시각 (TTL 300초) */
  expiresAt: string;
  createdAt: string;
}

export interface BookingPayment {
  paymentId: string;
  status: PaymentStatus;
  cardLast4?: string | null;
  failureCode?: FailureCode;
  approvedAt?: string | null;
  canceledAt?: string | null;
}

export interface Booking {
  bookingId: number;
  screeningId: number;
  status: BookingStatus;
  /** 점유 중(PENDING·CONFIRMED)일 때만 채워진다. 끝난 예매는 [] */
  seatIds: number[];
  seatLabels: string[];
  seatCount: number;
  amount: number;
  expiresAt: string;
  createdAt: string;
  confirmedAt?: string | null;
  canceledAt?: string | null;
  payment?: BookingPayment | null;
}

export interface PayRequest {
  method: "CARD";
  card: {
    /** 모의 카드. 저장·로그하지 않는다 */
    number: string;
    /** MM/YY */
    expiry: string;
    holder: string;
  };
}

/** BKG-10 결과. 거절도 200이고 `status`로 구분한다 */
export interface PayResult {
  bookingId: number;
  status: "CONFIRMED" | "PAYMENT_FAILED";
  confirmedAt?: string;
  seatLabels?: string[];
  payment: BookingPayment;
}

export interface CancelResult {
  bookingId: number;
  status: "CANCELED";
  canceledAt: string;
  payment?: { paymentId: string; status: PaymentStatus } | null;
}
