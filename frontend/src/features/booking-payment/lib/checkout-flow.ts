import type {
  Checkout,
  CheckoutAccepted,
  CheckoutRequest,
  FailureCode,
  PaymentPrepareResult,
} from "@/entities/booking";
import { ApiError, NetworkError, messageForError } from "@/shared/api";
import type { PayOutcome } from "./pay-flow";
import type { OpenTossPay } from "./portone";

/** 진행 중 polling 간격 기본값 — 서버가 `pollAfterMs`를 주면 그 값을 따른다 */
export const CHECKOUT_POLL_MS = 1000;
/** polling 상한 (명세 v1.5 21장: 1초 × 최대 30초). 넘으면 「결과 확인 중」으로 끝내고 다시 확인 버튼을 둔다 */
export const CHECKOUT_POLL_LIMIT_MS = 30_000;

/** 체크아웃 진행 단계 — 화면 문구를 나눈다 */
export type CheckoutStage = "starting" | "progress" | "window" | "confirming";

/**
 * 체크아웃의 끝. 결제 결과는 기존 결제({@link PayOutcome})와 같은 모양으로 돌려준다 — 결과 화면(W6)을 그대로 쓴다.
 * - `again`: 예매·좌석은 그대로이고 같은 화면에서 다시 결제한다(쿠폰 거절 · 결제창 취소·PG 거절 뒤 재결제)
 *
 * 화면은 동기 결제(`/payments`·`/pay`)를 부르지 않는다 — 동기 경로는 k6·테스트 전용이다(D19).
 */
export type CheckoutOutcome =
  | PayOutcome
  | { kind: "again"; state: "COUPON_REJECTED" | "PAYMENT_RETRYABLE"; message: string; retryable: boolean };

/** 체크아웃 경로가 없을 때(플래그 off → 404)·결제 서비스가 응답하지 않을 때 문구 */
export const PAYMENT_SERVICE_UNAVAILABLE = "결제 서비스에 문제가 있어 지금은 결제할 수 없어요. 잠시 후 다시 시도해 주세요. 돈은 나가지 않았어요.";

export interface CheckoutFlowDeps {
  start: (bookingId: number, body: CheckoutRequest, idempotencyKey: string) => Promise<CheckoutAccepted>;
  get: (bookingId: number) => Promise<Checkout>;
  confirm: (bookingId: number, paymentId: string, idempotencyKey: string) => Promise<CheckoutAccepted>;
  open: OpenTossPay;
  redirectUrl: string;
  wait: (ms: number) => Promise<void>;
  /** 경과 시간 계산용 (테스트에서 바꾼다) */
  now: () => number;
  newKey: () => string;
  onStage?: (stage: CheckoutStage) => void;
}

/**
 * 체크아웃 시작 → polling → (토스페이) 결제창 → 확인 요청 → polling (ADR-116, 명세 v1.5 21장).
 * 요청 스레드는 결제를 기다리지 않는다(202) — 결과는 `GET …/checkout`으로 본다.
 */
export async function startCheckoutFlow(
  bookingId: number,
  couponIssueId: string | null,
  deps: CheckoutFlowDeps,
): Promise<CheckoutOutcome> {
  deps.onStage?.("starting");
  const body: CheckoutRequest = couponIssueId ? { method: "TOSSPAY", couponIssueId } : { method: "TOSSPAY" };
  try {
    // 시작마다 새 키 — 쿠폰 거절·재결제 뒤 다시 시작할 때도 새 키여야 새 체크아웃이 된다(같은 키면 첫 응답이 돌아온다)
    await deps.start(bookingId, body, deps.newKey());
  } catch (error) {
    const failed = startFailure(error);
    if (failed) return failed;
  }
  return followCheckout(bookingId, deps);
}

/**
 * 모바일 결제창이 이 페이지로 돌아왔을 때(`?paymentId=…`) — 확인 요청 뒤 polling.
 * 결제창이 취소로 닫혔어도 부른다. 결과는 payment가 포트원에서 확인한다
 */
export async function completeCheckoutFlow(
  bookingId: number,
  paymentId: string,
  closedMessage: string | null,
  deps: CheckoutFlowDeps,
): Promise<CheckoutOutcome> {
  deps.onStage?.("confirming");
  await requestConfirm(bookingId, paymentId, deps);
  return followCheckout(bookingId, deps, { closedMessage, openedPaymentId: paymentId });
}

/** 새로고침·「결과 다시 확인」 — 진행 중인 체크아웃을 이어서 본다(새로 시작하지 않는다) */
export function resumeCheckoutFlow(bookingId: number, deps: CheckoutFlowDeps): Promise<CheckoutOutcome> {
  return followCheckout(bookingId, deps);
}

interface FollowState {
  closedMessage: string | null;
  /** 이미 결제창을 연 paymentId — 같은 결제로 창을 두 번 열지 않는다 */
  openedPaymentId: string | null;
}

async function followCheckout(
  bookingId: number,
  deps: CheckoutFlowDeps,
  initial: Partial<FollowState> = {},
): Promise<CheckoutOutcome> {
  const state: FollowState = { closedMessage: null, openedPaymentId: null, ...initial };
  deps.onStage?.(state.openedPaymentId ? "confirming" : "progress");
  let deadline = deps.now() + CHECKOUT_POLL_LIMIT_MS;

  for (;;) {
    let checkout: Checkout | null = null;
    try {
      checkout = await deps.get(bookingId);
    } catch (error) {
      // 체크아웃이 아예 없다(시작 요청이 닿지 않았다) — 돈은 움직이지 않았으니 다시 누르면 된다
      if (error instanceof ApiError && error.status === 404) {
        return { kind: "error", message: "결제를 시작하지 못했어요. 다시 시도해 주세요." };
      }
      // 그 밖의 조회 실패(5xx·네트워크)는 삼키고 다음 차례에 다시 본다 — 결과를 모르는 것이지 실패가 아니다
    }

    if (checkout) {
      const settled = settledOutcome(checkout);
      if (settled) return settled;

      const portone = checkout.payment?.portone ?? null;
      if (checkout.state === "READY_TO_PAY" && portone && state.openedPaymentId !== portone.paymentId) {
        state.openedPaymentId = portone.paymentId;
        deps.onStage?.("window");
        const result = await openWindow(checkout, portone, deps);
        // 모바일은 결제창이 페이지를 떠났다 — 돌아온 주소(`?paymentId=…`)에서 이어서 확인한다
        if (result.kind === "redirected") return { kind: "unknown" };
        if (result.kind === "closed") state.closedMessage = result.message;
        deps.onStage?.("confirming");
        await requestConfirm(bookingId, portone.paymentId, deps);
        // 결제창에 머문 시간은 빼고 다시 30초를 센다
        deadline = deps.now() + CHECKOUT_POLL_LIMIT_MS;
        continue;
      }
    }

    if (deps.now() >= deadline) {
      // 30초 안에 끝나지 않았다 — 결제창을 닫았다면 그 문구로, 아니면 「결과 확인 중」 (중복 결제는 되지 않는다)
      return state.closedMessage !== null ? { kind: "windowClosed", message: state.closedMessage } : { kind: "unknown" };
    }
    await deps.wait(checkout?.pollAfterMs ?? CHECKOUT_POLL_MS);
  }
}

/** 끝난 상태면 결과, 진행 중이면(모르는 값 포함) null */
function settledOutcome(checkout: Checkout): CheckoutOutcome | null {
  const { failure } = checkout;
  switch (checkout.state) {
    case "CONFIRMED":
      return { kind: "confirmed", bookingId: checkout.bookingId, seatLabels: [] };
    case "FAILED":
      return failure?.code === "PAYMENT_DECLINED"
        ? { kind: "declined", failureCode: (failure.reason ?? null) as FailureCode }
        : { kind: "declined", failureCode: null, message: failedMessage(failure?.code) };
    case "EXPIRED":
      return { kind: "expired", refunded: false, refundNotice: checkout.refundNotice };
    case "COUPON_REJECTED":
      return { kind: "again", state: "COUPON_REJECTED", message: couponRejectedMessage(failure?.reason), retryable: checkout.retryable };
    case "PAYMENT_RETRYABLE":
      return { kind: "again", state: "PAYMENT_RETRYABLE", message: retryableMessage(failure?.reason), retryable: checkout.retryable };
    default:
      return null;
  }
}

async function openWindow(checkout: Checkout, portone: NonNullable<NonNullable<Checkout["payment"]>["portone"]>, deps: CheckoutFlowDeps) {
  // 기존 결제창 함수(포트원 SDK·개발용 가짜 창)를 그대로 쓴다 — 금액·주문명·paymentId는 서버가 정한 값 그대로
  const prepared: PaymentPrepareResult = {
    bookingId: checkout.bookingId,
    paymentId: portone.paymentId,
    amount: portone.totalAmount,
    orderName: portone.orderName,
    expiresAt: checkout.expiresAt,
    checkout: {
      provider: "PORTONE",
      storeId: portone.storeId,
      channelKey: portone.channelKey,
      payMethod: portone.payMethod,
      currency: portone.currency,
    },
  };
  try {
    return await deps.open(prepared, deps.redirectUrl);
  } catch (error) {
    // SDK를 못 불렀다(네트워크·차단) — 결제창이 열리지 않았으니 돈은 나가지 않았다. 확인은 서버에 맡긴다
    return { kind: "closed" as const, message: messageForError(error) };
  }
}

/**
 * 결제창이 닫힌 뒤 확인 요청. 같은 키로 한 번만 다시 보낸다(응답을 못 받았을 때).
 * 실패해도 멈추지 않는다 — 포트원 웹훅·payment 스윕이 결제를 닫고 결과를 알리므로 polling으로 본다
 */
async function requestConfirm(bookingId: number, paymentId: string, deps: CheckoutFlowDeps): Promise<void> {
  const key = deps.newKey();
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await deps.confirm(bookingId, paymentId, key);
      return;
    } catch (error) {
      // 4xx(CHECKOUT_NOT_READY = 이미 확인 중·끝남 등)는 다시 보내도 같다 — polling이 결과를 보여 준다
      if (error instanceof ApiError && error.status < 500) return;
    }
  }
}

/** 시작 요청 실패 — 끝내야 하면 결과, 이어서 polling하면 null */
function startFailure(error: unknown): CheckoutOutcome | null {
  // 응답을 못 받았다 — 서버에 닿았을 수 있으니 polling으로 확인한다(없으면 404로 「다시 시도」)
  if (error instanceof NetworkError) return null;
  if (!(error instanceof ApiError)) return { kind: "error", message: messageForError(error) };

  switch (error.code) {
    case "NOT_FOUND":
      // 예매는 이미 읽었다 — 404는 체크아웃 경로가 꺼진 것(플래그 off). 동기 결제로 대신하지 않고 결제 서비스 문제로 알린다
      return { kind: "error", message: PAYMENT_SERVICE_UNAVAILABLE };
    case "CHECKOUT_IN_PROGRESS":
      return null; // 이미 진행 중(다른 탭·재시도) — 새로 시작하지 않고 그 체크아웃을 본다
    case "PAYMENT_PATH_CONFLICT":
      // 이 예매에는 동기 결제가 이미 붙어 있다 — 결과는 예매 조회로 확인한다(새 결제를 시작하지 않는다)
      return { kind: "unknown" };
    case "SCREENING_STARTED":
      return { kind: "error", message: "예매가 마감된 회차예요(상영 시작 5분 전부터). 다른 회차를 골라 주세요." };
    case "BOOKING_STATE_CONFLICT":
      if (error.details.some((d) => d.reason === "HOLD_EXPIRED")) return { kind: "expired", refunded: false };
      if (error.details.some((d) => d.reason === "RETRY_LIMIT")) {
        return { kind: "error", message: "결제는 예매마다 3번까지 시도할 수 있어요. 좌석을 다시 골라 주세요." };
      }
      return { kind: "error", message: "예매 상태가 바뀌어 결제를 진행할 수 없습니다." };
    case "IDEMPOTENCY_IN_PROGRESS":
      return null; // 같은 요청을 서버가 처리 중 — 체크아웃이 생기면 polling이 이어 간다
    default:
      // 5xx(catalog 응답 없음 등)는 다시 눌러도 된다 — 돈은 아직 움직이지 않았다
      return { kind: "error", message: messageForError(error) };
  }
}

const COUPON_REJECT_REASON: Record<string, string> = {
  EXPIRED: "기간이 지난 쿠폰이에요.",
  NOT_OWNER: "내 쿠폰이 아니에요.",
  NOT_ISSUED: "쓸 수 없는 상태의 쿠폰이에요(이미 사용·예약됨).",
  ALREADY_RELEASED: "이미 반환된 쿠폰이에요.",
};

function couponRejectedMessage(reason: string | null | undefined): string {
  const why = (reason && COUPON_REJECT_REASON[reason]) || "쿠폰을 쓸 수 없어요.";
  return `${why} 다른 쿠폰을 고르거나 쿠폰 없이 결제해 주세요. 고른 좌석은 그대로예요.`;
}

function retryableMessage(reason: string | null | undefined): string {
  if (reason === "USER_CANCELED") return "결제를 취소했어요. 좌석은 그대로니 다시 결제할 수 있어요.";
  if (reason === "PG_DECLINED") return "토스페이에서 결제가 승인되지 않았어요. 좌석은 그대로니 다시 결제할 수 있어요.";
  return "결제가 완료되지 않았어요. 좌석은 그대로니 다시 결제할 수 있어요.";
}

function failedMessage(code: string | undefined): string {
  if (code === "STEP_TIMEOUT") return "결제 준비가 늦어져 취소했어요. 돈은 나가지 않았어요. 좌석을 다시 골라 주세요.";
  if (code === "COUPON_NOT_USABLE") return "쿠폰을 쓸 수 없어 결제를 진행하지 못했어요. 좌석을 다시 골라 주세요.";
  return "결제가 완료되지 않았습니다. 좌석을 다시 선택해 주세요.";
}
