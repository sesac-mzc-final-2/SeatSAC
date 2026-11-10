import type { MyBooking, MyBookingStatus } from "../model/types";

export type BookingStatusTone = "confirmed" | "muted" | "canceled";

const STATUS: Record<MyBookingStatus, { label: string; tone: BookingStatusTone }> = {
  CONFIRMED: { label: "예매 완료", tone: "confirmed" },
  PAYMENT_FAILED: { label: "결제 실패", tone: "muted" },
  EXPIRED: { label: "시간 초과", tone: "muted" },
  CANCELED: { label: "취소됨", tone: "canceled" },
};

/** 상태 배지 4종 (W8): 예매 완료는 포인트 컬러, 결제 실패·시간 초과는 회색, 취소됨은 취소선 */
export const bookingStatusLabel = (status: MyBookingStatus): string => STATUS[status]?.label ?? status;
export const bookingStatusTone = (status: MyBookingStatus): BookingStatusTone => STATUS[status]?.tone ?? "muted";

/** 「예매 취소」(BKG-07)는 예매 완료 + 상영 시작 전에만. 회차 정보가 없으면(Catalog 실패) 보여 주지 않는다 */
export function canCancelBooking(booking: Pick<MyBooking, "status" | "screening">, now: Date = new Date()): boolean {
  if (booking.status !== "CONFIRMED" || !booking.screening) return false;
  return new Date(booking.screening.startsAt).getTime() > now.getTime();
}

/** 결제 실패·시간 초과는 좌석이 풀렸으니 다시 고를 수 있게 상영시간표 링크를 둔다 */
export const isRetryableBooking = (status: MyBookingStatus): boolean =>
  status === "PAYMENT_FAILED" || status === "EXPIRED";
