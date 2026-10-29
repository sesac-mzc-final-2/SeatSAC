import type { Booking, PayRequest, PayResult } from "@/entities/booking";
import { ApiError, messageForError } from "@/shared/api";

/** 503 PAYMENT_RESULT_UNKNOWN 뒤 결과 확인: 2초 간격 최대 5회 (계약 BKG-10 503) */
export const POLL_INTERVAL_MS = 2000;
export const POLL_ATTEMPTS = 5;

/**
 * 결제 요청의 끝 상태. 거절(`declined`)은 고장이 아니라 정상 흐름이다 —
 * 서버도 200 + `status`로 돌려준다.
 */
export type PayOutcome =
  | { kind: "confirmed"; bookingId: number; seatLabels: string[]; confirmedAt?: string }
  /** 카드 거절·잔액 부족. 좌석은 풀렸으니 다시 고르는 흐름으로 보낸다 */
  | { kind: "declined"; failureCode: PayResult["payment"]["failureCode"] }
  /** 선점이 먼저 만료됐다. 승인됐더라도 서버가 환불한다 */
  | { kind: "expired"; refunded: boolean }
  /** 2초 × 5회 확인에도 결과가 안 나왔다 — 예매 내역에서 다시 확인하게 안내 */
  | { kind: "unknown" }
  | { kind: "error"; message: string };

export interface PayFlowDeps {
  pay: (bookingId: number, body: PayRequest, idempotencyKey: string) => Promise<PayResult>;
  load: (bookingId: number) => Promise<Booking>;
  wait: (ms: number) => Promise<void>;
  newKey: () => string;
  /** 확인 횟수를 화면에 보여 주기 위한 알림 (1부터) */
  onChecking?: (attempt: number) => void;
}

const fromPayResult = (result: PayResult): PayOutcome =>
  result.status === "CONFIRMED"
    ? {
        kind: "confirmed",
        bookingId: result.bookingId,
        seatLabels: result.seatLabels ?? [],
        confirmedAt: result.confirmedAt,
      }
    : { kind: "declined", failureCode: result.payment.failureCode ?? null };

const fromBooking = (booking: Booking): PayOutcome | null => {
  switch (booking.status) {
    case "CONFIRMED":
      return {
        kind: "confirmed",
        bookingId: booking.bookingId,
        seatLabels: booking.seatLabels,
        confirmedAt: booking.confirmedAt ?? undefined,
      };
    case "PAYMENT_FAILED":
      return { kind: "declined", failureCode: booking.payment?.failureCode ?? null };
    case "EXPIRED":
      return { kind: "expired", refunded: booking.payment?.status === "CANCELED" };
    case "CANCELED":
      return { kind: "expired", refunded: booking.payment?.status === "CANCELED" };
    case "PENDING":
      return null; // 아직 모른다 → 더 기다린다
  }
};

const detail = (error: ApiError, field: string): unknown =>
  error.details.find((item) => item.field === field)?.value;

/**
 * 결제 결과를 「알 수 없음」으로 끝내지 않기 위한 확인 절차.
 * 예매 상태가 PENDING에서 벗어날 때까지 {@link POLL_ATTEMPTS}번 다시 조회한다
 * (서버가 PENDING + 결제 요청이 있으면 Payment를 조회해 반영한 뒤 응답한다).
 */
async function pollUntilSettled(bookingId: number, deps: PayFlowDeps, waitFirst: boolean): Promise<PayOutcome> {
  for (let attempt = 1; attempt <= POLL_ATTEMPTS; attempt += 1) {
    if (waitFirst || attempt > 1) await deps.wait(POLL_INTERVAL_MS);
    deps.onChecking?.(attempt);
    try {
      const settled = fromBooking(await deps.load(bookingId));
      if (settled) return settled;
    } catch (error) {
      // 확인 중 오류는 삼키고 다음 차례에 다시 본다 — 마지막까지 모르면 unknown
      if (attempt === POLL_ATTEMPTS) return { kind: "error", message: messageForError(error) };
    }
  }
  return { kind: "unknown" };
}

/**
 * BKG-10 결제 요청 한 번 + 결과 확정까지. 재시도 버튼은 두지 않는다(예매당 결제 1회, ADR-24) —
 * 거절되면 좌석을 다시 고르는 흐름으로 돌려보낸다.
 */
export async function settlePayment(
  bookingId: number,
  body: PayRequest,
  deps: PayFlowDeps,
): Promise<PayOutcome> {
  try {
    return fromPayResult(await deps.pay(bookingId, body, deps.newKey()));
  } catch (error) {
    if (!(error instanceof ApiError)) return { kind: "error", message: messageForError(error) };

    switch (error.code) {
      case "PAYMENT_RESULT_UNKNOWN":
        // 요청은 갔고 결과만 모르는 상태 — 2초 뒤부터 확인한다
        return pollUntilSettled(bookingId, deps, true);
      case "PAYMENT_ALREADY_REQUESTED":
        // 이미 결제가 요청된 예매 — 결과는 조회로 확인한다(다시 결제하지 않는다)
        return pollUntilSettled(bookingId, deps, false);
      case "BOOKING_STATE_CONFLICT": {
        if (detail(error, "status") === "EXPIRED") {
          return { kind: "expired", refunded: detail(error, "refunded") === true };
        }
        return { kind: "error", message: "예매 상태가 바뀌어 결제를 진행할 수 없습니다." };
      }
      case "IDEMPOTENCY_IN_PROGRESS":
        return { kind: "error", message: "직전 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요." };
      default:
        return { kind: "error", message: messageForError(error) };
    }
  }
}
