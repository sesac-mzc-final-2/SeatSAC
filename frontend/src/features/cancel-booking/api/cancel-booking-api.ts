import { http } from "@/shared/api";

/** contracts/openapi/booking.openapi.yaml — CancelResult (BKG-07) */
export interface CancelResult {
  bookingId: number;
  status: "CANCELED";
  canceledAt: string;
  payment?: { paymentId: string; status: string };
}

/**
 * BKG-07 예매 취소 + 환불. CONFIRMED·상영 시작 전만.
 * 400 SCREENING_STARTED · 409 BOOKING_STATE_CONFLICT · 503 SERVICE_UNAVAILABLE(화면 재시도).
 * 상태 전이 자체가 멱등이라 Idempotency-Key 는 선택이지만 재시도 때 같은 값을 보내도록 붙인다
 */
export const cancelBooking = (bookingId: number, idempotencyKey: string) =>
  http<CancelResult>(`/bookings/${bookingId}/cancel`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
  });
