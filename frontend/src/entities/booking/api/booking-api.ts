import { buildUrl, http } from "@/shared/api";
import type { Booking, CancelResult, HoldResult, PayRequest, PayResult, SeatSnapshot } from "../model/types";

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
