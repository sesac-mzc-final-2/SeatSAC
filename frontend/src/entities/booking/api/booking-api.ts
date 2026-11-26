import { buildUrl, http } from "@/shared/api";
import type {
  Booking,
  CancelResult,
  Checkout,
  CheckoutAccepted,
  CheckoutRequest,
  HoldResult,
  PaymentPrepareResult,
  PayRequest,
  PayResult,
  SeatSnapshot,
} from "../model/types";

/** BKG-01 좌석 현황 스냅샷 + seq. 인증 없이 볼 수 있다 */
export const getSeatSnapshot = (screeningId: number, signal?: AbortSignal) =>
  http<SeatSnapshot>(`/screenings/${screeningId}/seats`, { signal, auth: false });

/** BKG-05 SSE 주소. `EventSource`는 헤더를 못 붙여서 인증 없는 경로여야 한다 */
export const seatStreamUrl = (screeningId: number): string =>
  buildUrl(`/screenings/${screeningId}/seats/stream`);

/**
 * BKG-02 좌석 선점 (최대 4석). `Idempotency-Key`가 필수다 —
 * 같은 키로 다시 보내면 첫 응답을 그대로 받는다(네트워크가 끊겨 결과를 모를 때 재시도용).
 */
export const holdSeats = (
  body: { screeningId: number; seatIds: number[] },
  idempotencyKey: string,
  signal?: AbortSignal,
) => http<HoldResult>("/seats/hold", { method: "POST", body, headers: { "Idempotency-Key": idempotencyKey }, signal });

/** 예매 단건 (본인만). 새로고침·결제 결과 재확인에 쓴다 */
export const getBooking = (bookingId: number, signal?: AbortSignal) =>
  http<Booking>(`/bookings/${bookingId}`, { signal });

/** BKG-03 선점 해제 → CANCELED */
export const releaseHold = (bookingId: number, idempotencyKey?: string, signal?: AbortSignal) =>
  http<CancelResult>(`/bookings/${bookingId}/hold`, {
    method: "DELETE",
    headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : undefined,
    signal,
  });

/**
 * 토스페이 결제 준비 — paymentId를 예매에 붙이고 결제창 값을 받는다(예매당 1회). 완료(`/pay`)와 다른 멱등 키를 쓴다
 * (본문이 달라 같은 키면 409 `IDEMPOTENCY_KEY_CONFLICT`)
 */
export const preparePayment = (bookingId: number, idempotencyKey: string, signal?: AbortSignal) =>
  http<PaymentPrepareResult>(`/bookings/${bookingId}/payments`, {
    method: "POST",
    body: { method: "TOSSPAY" },
    headers: { "Idempotency-Key": idempotencyKey },
    signal,
  });

/** BKG-10 결제 요청 (예매당 1회). 거절도 200 + `status: PAYMENT_FAILED` */
export const payBooking = (
  bookingId: number,
  body: PayRequest,
  idempotencyKey: string,
  signal?: AbortSignal,
) =>
  http<PayResult>(`/bookings/${bookingId}/pay`, {
    method: "POST",
    body,
    headers: { "Idempotency-Key": idempotencyKey },
    signal,
  });

/**
 * 체크아웃 시작 (비동기 결제 saga, 6.2.5·ADR-116) → 202. 요청마다 새 `Idempotency-Key` — 쿠폰 거절·재결제 뒤 다시 시작할 때도 새 키다.
 * 플래그 `APP_CHECKOUT_SAGA_ENABLED=false`면 404 (화면은 기존 동기 결제로 대신한다)
 */
export const startCheckout = (bookingId: number, body: CheckoutRequest, idempotencyKey: string, signal?: AbortSignal) =>
  http<CheckoutAccepted>(`/bookings/${bookingId}/checkout`, {
    method: "POST",
    body,
    headers: { "Idempotency-Key": idempotencyKey },
    signal,
  });

/** 체크아웃 상태 (polling) — payment·coupon을 부르지 않는 가벼운 조회다 */
export const getCheckout = (bookingId: number, signal?: AbortSignal) =>
  http<Checkout>(`/bookings/${bookingId}/checkout`, { signal });

/** 결제창이 닫힌 뒤(성공·취소 모두) 결제 확인 요청 → 202. 결과는 payment가 포트원에서 확인하고 polling으로 본다 */
export const confirmCheckout = (bookingId: number, paymentId: string, idempotencyKey: string, signal?: AbortSignal) =>
  http<CheckoutAccepted>(`/bookings/${bookingId}/checkout/confirm`, {
    method: "POST",
    body: { paymentId },
    headers: { "Idempotency-Key": idempotencyKey },
    signal,
  });
