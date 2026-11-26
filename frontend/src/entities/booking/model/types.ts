/** contracts/openapi/booking.openapi.yaml — BKG-01·02·03·05·10 */

export type SeatStatus = "AVAILABLE" | "HELD" | "RESERVED";

export type BookingStatus = "PENDING" | "CONFIRMED" | "PAYMENT_FAILED" | "EXPIRED" | "CANCELED";

export type PaymentStatus = "PROCESSING" | "APPROVED" | "FAILED" | "CANCELED";

/** 결제 수단 — 화면은 토스페이만. CARD는 모의 결제로 k6·분석 Job 전용(서버 키 헤더, ADR-112) — 화면은 보내지 않는다 */
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
  /** 체크아웃 saga 경로 예매면 가장 최근 체크아웃(없으면 null·없음). 상세는 `GET /bookings/{id}/checkout` (6.2.5) */
  checkout?: { checkoutId: string; state: CheckoutState } | null;
}

/**
 * 체크아웃 saga 상태 (ADR-116, 계약 `CheckoutState`). 열린 집합 — 모르는 값은 「진행 중」으로 보고 `pollAfterMs`를 따른다.
 * 진행 중: STARTED → COUPON_RESERVING → PAYMENT_PREPARING → READY_TO_PAY(결제창) → VERIFYING · CARD는 CHARGING.
 * 끝: CONFIRMED · FAILED · EXPIRED · COUPON_REJECTED(쿠폰만 거절, 다시 시작) · PAYMENT_RETRYABLE(다시 결제)
 */
export type CheckoutState =
  | "STARTED"
  | "COUPON_RESERVING"
  | "PAYMENT_PREPARING"
  | "READY_TO_PAY"
  | "CHARGING"
  | "VERIFYING"
  | "CONFIRMED"
  | "COMPENSATING"
  | "FAILED"
  | "EXPIRED"
  | "COUPON_REJECTED"
  | "PAYMENT_RETRYABLE"
  | (string & {});

/** `POST /bookings/{id}/checkout` 본문 — 화면은 토스페이만. 쿠폰은 `GET /coupons/me`의 `couponIssueId` 그대로 */
export interface CheckoutRequest {
  method: "TOSSPAY";
  couponIssueId?: string;
}

/** 체크아웃 시작·확인 요청의 202 응답 */
export interface CheckoutAccepted {
  checkoutId: string;
  bookingId: number;
  state: CheckoutState;
  pollAfterMs: number;
}

/** 포트원 `requestPayment`에 그대로 넘기는 공개 값 — `READY_TO_PAY`일 때만 온다 */
export interface CheckoutPortOne {
  storeId: string;
  channelKey: string;
  paymentId: string;
  orderName: string;
  totalAmount: number;
  currency: "KRW";
  payMethod: "EASY_PAY";
}

/**
 * 만료 때 환불 안내 (계약 `refundNotice`). null = 결제가 없었거나 안 된 것이 확인됨.
 * 「환불 완료」는 없다 — booking은 환불이 끝났는지 모른다
 */
export type RefundNotice = "WILL_REFUND_IF_PAID" | "REFUND_IN_PROGRESS" | (string & {}) | null;

/** `GET /bookings/{id}/checkout` — 가장 최근 체크아웃 (polling) */
export interface Checkout {
  checkoutId: string;
  bookingId: number;
  state: CheckoutState;
  step: string;
  method: string;
  /** 이 예매의 몇 번째 체크아웃인지 (상한 3) */
  attempt: number;
  /** 끝난 상태에서 같은 예매로 다시 시작할 수 있는지 (COUPON_REJECTED·PAYMENT_RETRYABLE, 선점 유효) */
  retryable: boolean;
  /** 서버가 확정한 금액 — 포트원 결제창 금액은 `final` */
  amount: { original: number; discount: number; final: number };
  coupon: { couponIssueId: string; discount: { kind: string; value: number } | null; state: string } | null;
  payment: { paymentId: string; portone: CheckoutPortOne | null } | null;
  failure: { code: string; reason: string | null } | null;
  refundNotice: RefundNotice;
  expiresAt: string;
  updatedAt: string;
  /** 진행 중 1000, 끝난 상태 null */
  pollAfterMs: number | null;
}

/** 토스페이 완료 확인 — 준비에서 받은 `paymentId`. 결과는 서버가 포트원에서 확인한다 */
export interface TossPayRequest {
  method: "TOSSPAY";
  paymentId: string;
}

/** BKG-10 결제 요청 — 화면은 토스페이 완료 확인만 보낸다 */
export type PayRequest = TossPayRequest;

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
