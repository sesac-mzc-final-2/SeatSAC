import type { BookingStatus, FailureCode } from "../model/types";

/** 1석 15,000원 (docs/conventions.md 6장) */
export const SEAT_PRICE = 15_000;

/** 한 번에 고를 수 있는 좌석 수 (ADR-11) */
export const MAX_SEATS = 4;

/** `30000` → `30,000원` */
export const formatAmount = (amount: number): string => `${amount.toLocaleString("ko-KR")}원`;

export const amountFor = (seatCount: number): number => seatCount * SEAT_PRICE;

const STATUS_LABEL: Record<BookingStatus, string> = {
  PENDING: "결제 대기",
  CONFIRMED: "예매 완료",
  PAYMENT_FAILED: "결제 실패",
  EXPIRED: "시간 초과",
  CANCELED: "취소됨",
};

export const bookingStatusLabel = (status: BookingStatus): string => STATUS_LABEL[status] ?? status;

const FAILURE_MESSAGE: Record<Exclude<FailureCode, null>, string> = {
  CARD_DECLINED: "카드가 거절되었습니다. 다른 카드로 다시 예매해 주세요.",
  INSUFFICIENT_BALANCE: "카드 잔액이 부족합니다. 다른 카드로 다시 예매해 주세요.",
};

/** 결제 실패 사유 — 모르는 코드는 공통 문구로 (계약에 값이 추가돼도 화면이 깨지지 않게) */
export const failureMessage = (code: FailureCode | undefined): string =>
  (code && FAILURE_MESSAGE[code]) || "결제가 완료되지 않았습니다. 좌석을 다시 선택해 주세요.";

/** 선점이 끝날 때까지 남은 초 (음수는 0) */
export function secondsLeft(expiresAt: string, now: number = Date.now()): number {
  const remain = Math.floor((new Date(expiresAt).getTime() - now) / 1000);
  return Number.isFinite(remain) && remain > 0 ? remain : 0;
}

/** `125` → `2:05` */
export function formatCountdown(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}
