import type { PaymentPrepareResult, TossPayRequest } from "@/entities/booking";
import { ApiError, NetworkError, messageForError } from "@/shared/api";
import { settlePayment, type PayFlowDeps, type PayOutcome } from "./pay-flow";
import type { TossPayWindowResult } from "./portone";

/** 결제 진행 단계 — 화면이 「결제창을 여는 중」·「결제 결과를 확인하는 중」을 나눠 보여 준다 */
export type TossPayStage = "preparing" | "window" | "confirming";

export interface TossPayFlowDeps extends PayFlowDeps {
  prepare: (bookingId: number, idempotencyKey: string) => Promise<PaymentPrepareResult>;
  open: (prepared: PaymentPrepareResult, redirectUrl: string) => Promise<TossPayWindowResult>;
  redirectUrl: string;
  onStage?: (stage: TossPayStage) => void;
}

/**
 * 토스페이 결제: ① 준비(paymentId·결제창 값) → ② 포트원 결제창 → ③ 완료 확인(`/pay`, 결과 확정까지).
 * ③은 결제창이 성공이든 취소든 부른다 — 결과는 서버가 포트원에서 확인한다. ①과 ③은 서로 다른 멱등 키를 쓴다.
 */
export async function startTossPay(
  bookingId: number,
  deps: TossPayFlowDeps,
): Promise<PayOutcome> {
  deps.onStage?.("preparing");
  let prepared: PaymentPrepareResult;
  try {
    prepared = await deps.prepare(bookingId, deps.newKey());
  } catch (error) {
    return prepareFailure(error);
  }

  deps.onStage?.("window");
  let result: TossPayWindowResult;
  try {
    result = await deps.open(prepared, deps.redirectUrl);
  } catch (error) {
    // SDK를 못 불렀다(네트워크·차단). 결제창이 열리지 않았으니 돈은 나가지 않았다 — 그래도 서버에 확인은 맡긴다
    result = { kind: "closed", message: messageForError(error) };
  }
  if (result.kind === "redirected") return { kind: "unknown" };

  return completeTossPay(bookingId, prepared.paymentId, result.kind === "closed" ? result.message : null, deps);
}

/**
 * ③ 완료 확인만 — 모바일 결제창이 이 페이지로 돌아왔을 때(`?paymentId=…`)도 여기서 시작한다.
 *
 * @param closedMessage 결제창이 취소·실패로 닫혔으면 그 문구. 서버도 결과를 모르면(503) 「결제를 마치지 않았어요」로 끝낸다
 */
export async function completeTossPay(
  bookingId: number,
  paymentId: string,
  closedMessage: string | null,
  deps: PayFlowDeps & { onStage?: (stage: TossPayStage) => void },
): Promise<PayOutcome> {
  deps.onStage?.("confirming");
  const body: TossPayRequest = { method: "TOSSPAY", paymentId };
  const outcome = await settlePayment(bookingId, body, deps);
  if (outcome.kind === "unknown" && closedMessage !== null) {
    return { kind: "windowClosed", message: closedMessage };
  }
  return outcome;
}

function prepareFailure(error: unknown): PayOutcome {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "SCREENING_STARTED":
        return { kind: "error", message: "예매가 마감된 회차예요(상영 시작 5분 전부터). 다른 회차를 골라 주세요." };
      case "PAYMENT_ALREADY_REQUESTED":
        // 이미 결제를 시작한 예매 — 결과를 모르는 채 새 결제창을 열면 안 된다(예매당 1회)
        return { kind: "unknown" };
      case "BOOKING_STATE_CONFLICT":
        return error.details.some((d) => d.reason === "HOLD_EXPIRED")
          ? { kind: "expired", refunded: false }
          : { kind: "error", message: "예매 상태가 바뀌어 결제를 진행할 수 없습니다." };
      default:
        return { kind: "error", message: messageForError(error) };
    }
  }
  // 준비 응답이 끊겼다 — paymentId는 붙었을 수 있지만 결제창은 열리지 않았으니 돈은 나가지 않았다
  if (error instanceof NetworkError) return { kind: "unknown" };
  return { kind: "error", message: messageForError(error) };
}

