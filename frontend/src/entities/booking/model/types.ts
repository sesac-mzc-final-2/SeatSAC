/** contracts/openapi/booking.openapi.yaml — BKG-01·02·03·05·10 */

export type SeatStatus = "AVAILABLE" | "HELD" | "RESERVED";

export type BookingStatus = "PENDING" | "CONFIRMED" | "PAYMENT_FAILED" | "EXPIRED" | "CANCELED";

export type PaymentStatus = "PROCESSING" | "APPROVED" | "FAILED" | "CANCELED";

/** 결제 수단 — 화면은 토스페이만, CARD는 모의 결제(관리자·테스트 전용, ADR-112) */
export type PaymentMethod = "CARD" | "TOSSPAY";

/**
 * 거절 사유. 앞의 둘은 모의 카드, 뒤의 셋은 포트원(토스페이) — PG 거절 · 결제창 취소 · 금액 불일치(서버가 자동 환불).
 * 계약은 x-extensible-enum이라 모르는 값이 올 수 있다 → 화면은 모르는 값을 공통 문구로 보여 준다
 */
export type FailureCode =
  | "CARD_DECLINED"
  | "INSUFFICIENT_BALANCE"
  | "PG_DECLINED"
  | "USER_CANCELED"
  | "AMOUNT_MISMATCH"
  | null;

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
  method?: PaymentMethod;
  /** CARD만. TOSSPAY는 null */
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

/** 모의 카드 결제 (관리자·테스트 전용). 서버가 ADMIN이나 테스트 키가 아니면 400 `MOCK_NOT_ALLOWED` */
export interface CardPayRequest {
  method: "CARD";
  card: {
    /** 모의 카드. 저장·로그하지 않는다 */
    number: string;
    /** MM/YY */
    expiry: string;
    holder: string;
  };
}

/** 토스페이 완료 확인 — 준비에서 받은 `paymentId`. 결과는 서버가 포트원에서 확인한다 */
export interface TossPayRequest {
  method: "TOSSPAY";
  paymentId: string;
}

export type PayRequest = CardPayRequest | TossPayRequest;

/** `POST /bookings/{id}/payments` — 포트원 결제창에 넘길 값. 돈은 아직 움직이지 않았다 */
export interface PaymentPrepareResult {
  bookingId: number;
  paymentId: string;
  amount: number;
  orderName: string;
  /** 선점 만료. 이 안에 결제창을 끝내지 않으면 승인돼도 환불된다 */
  expiresAt: string;
  checkout: {
    provider: "PORTONE";
    storeId: string;
    channelKey: string;
    payMethod: "EASY_PAY";
    currency: "KRW";
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
