import { describe, expect, it, vi } from "vitest";
import type { Checkout, CheckoutAccepted } from "@/entities/booking";
import { ApiError, NetworkError } from "@/shared/api";
import {
  CHECKOUT_POLL_LIMIT_MS,
  PAYMENT_SERVICE_UNAVAILABLE,
  completeCheckoutFlow,
  resumeCheckoutFlow,
  startCheckoutFlow,
  type CheckoutFlowDeps,
} from "./checkout-flow";

const PAYMENT_ID = "3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13";

const accepted: CheckoutAccepted = { checkoutId: "c-1", bookingId: 1042, state: "STARTED", pollAfterMs: 1000 };

const checkout = (over: Partial<Checkout> = {}): Checkout => ({
  checkoutId: "c-1",
  bookingId: 1042,
  state: "PAYMENT_PREPARING",
  step: "STARTED",
  method: "TOSSPAY",
  attempt: 1,
  retryable: false,
  amount: { original: 30000, discount: 0, final: 30000 },
  coupon: null,
  payment: null,
  failure: null,
  refundNotice: null,
  expiresAt: "2026-11-26T14:05:00+09:00",
  updatedAt: "2026-11-26T14:00:01+09:00",
  pollAfterMs: 1000,
  ...over,
});

const ready = checkout({
  state: "READY_TO_PAY",
  step: "PAYMENT_PREPARED",
  amount: { original: 30000, discount: 15000, final: 15000 },
  payment: {
    paymentId: PAYMENT_ID,
    portone: {
      storeId: "store-test",
      channelKey: "channel-test",
      paymentId: PAYMENT_ID,
      orderName: "SeatSAC 영화 예매 2석",
      totalAmount: 15000,
      currency: "KRW",
      payMethod: "EASY_PAY",
    },
  },
});
const verifying = checkout({ state: "VERIFYING", payment: { paymentId: PAYMENT_ID, portone: null } });
const confirmed = checkout({ state: "CONFIRMED", pollAfterMs: null });

const apiError = (status: number, code: string, details: { field: string; value?: unknown; reason?: string }[] = []) =>
  new ApiError(status, { code, message: "개발용", traceId: "a".repeat(32), details });

/** `get`이 차례로 돌려줄 체크아웃 (마지막 값은 계속 반복) */
function sequence(...items: (Checkout | Error)[]) {
  let i = 0;
  return vi.fn(async () => {
    const item = items[Math.min(i, items.length - 1)];
    i += 1;
    if (item instanceof Error) throw item;
    return item;
  });
}

function deps(over: Partial<CheckoutFlowDeps> = {}): CheckoutFlowDeps & { clock: { t: number } } {
  let key = 0;
  const clock = { t: 0 };
  return {
    clock,
    start: vi.fn().mockResolvedValue(accepted),
    get: sequence(checkout(), ready, verifying, confirmed),
    confirm: vi.fn().mockResolvedValue({ ...accepted, state: "VERIFYING" }),
    open: vi.fn().mockResolvedValue({ kind: "done" }),
    redirectUrl: "http://localhost:5173/bookings/1042/pay",
    // 기다리면 시계가 그만큼 간다
    wait: vi.fn(async (ms: number) => {
      clock.t += ms;
    }),
    now: () => clock.t,
    newKey: () => `key-${++key}`,
    ...over,
  };
}

describe("startCheckoutFlow", () => {
  it("시작(202) → polling → READY_TO_PAY에서 서버 값으로 결제창 → 확인 요청 → 확정", async () => {
    const d = deps();
    const outcome = await startCheckoutFlow(1042, "coupon-1", d);

    expect(outcome).toEqual({ kind: "confirmed", bookingId: 1042, seatLabels: [] });
    expect(d.start).toHaveBeenCalledWith(1042, { method: "TOSSPAY", couponIssueId: "coupon-1" }, "key-1");
    // 결제창은 서버가 정한 금액(할인 뒤 최종)·paymentId 그대로
    expect(d.open).toHaveBeenCalledTimes(1);
    expect(d.open).toHaveBeenCalledWith(
      expect.objectContaining({ paymentId: PAYMENT_ID, amount: 15000, orderName: "SeatSAC 영화 예매 2석" }),
      d.redirectUrl,
    );
    // 확인 요청은 시작과 다른 키
    expect(d.confirm).toHaveBeenCalledWith(1042, PAYMENT_ID, "key-2");
  });

  it("쿠폰이 없으면 본문에 couponIssueId를 넣지 않는다", async () => {
    const d = deps();
    await startCheckoutFlow(1042, null, d);
    expect(d.start).toHaveBeenCalledWith(1042, { method: "TOSSPAY" }, "key-1");
  });

  it("READY_TO_PAY가 여러 번 보여도 같은 결제로 결제창을 두 번 열지 않는다", async () => {
    const d = deps({ get: sequence(ready, ready, ready, confirmed) });
    await startCheckoutFlow(1042, null, d);
    expect(d.open).toHaveBeenCalledTimes(1);
    expect(d.confirm).toHaveBeenCalledTimes(1);
  });

  it("체크아웃 경로가 없으면(404, 플래그 off) 결제 서비스 문제로 안내한다 — 동기 결제로 대신하지 않는다", async () => {
    const d = deps({ start: vi.fn().mockRejectedValue(apiError(404, "NOT_FOUND")) });
    expect(await startCheckoutFlow(1042, null, d)).toEqual({ kind: "error", message: PAYMENT_SERVICE_UNAVAILABLE });
    expect(d.get).not.toHaveBeenCalled();
    expect(d.open).not.toHaveBeenCalled();
  });

  it("이미 진행 중(409 CHECKOUT_IN_PROGRESS)이면 새로 시작하지 않고 그 체크아웃을 본다", async () => {
    const d = deps({ start: vi.fn().mockRejectedValue(apiError(409, "CHECKOUT_IN_PROGRESS")), get: sequence(confirmed) });
    expect(await startCheckoutFlow(1042, null, d)).toMatchObject({ kind: "confirmed" });
  });

  it("시작 응답을 못 받으면(네트워크) polling으로 확인한다 — 체크아웃이 없으면(404) 다시 시도 안내", async () => {
    const d = deps({ start: vi.fn().mockRejectedValue(new NetworkError(new TypeError("Failed to fetch"))), get: sequence(apiError(404, "NOT_FOUND")) });
    expect(await startCheckoutFlow(1042, null, d)).toMatchObject({ kind: "error" });
  });

  it("선점 만료(409 HOLD_EXPIRED)·재시도 한도(RETRY_LIMIT)", async () => {
    const expired = deps({
      start: vi.fn().mockRejectedValue(apiError(409, "BOOKING_STATE_CONFLICT", [{ field: "status", value: "PENDING", reason: "HOLD_EXPIRED" }])),
    });
    expect(await startCheckoutFlow(1042, null, expired)).toEqual({ kind: "expired", refunded: false });

    const limit = deps({
      start: vi.fn().mockRejectedValue(apiError(409, "BOOKING_STATE_CONFLICT", [{ field: "attempt", value: 3, reason: "RETRY_LIMIT" }])),
    });
    expect(await startCheckoutFlow(1042, null, limit)).toMatchObject({ kind: "error", message: expect.stringContaining("3번") });
  });

  it("동기 결제가 이미 붙은 예매(409 PAYMENT_PATH_CONFLICT)는 새 결제 없이 결과 확인으로", async () => {
    const d = deps({ start: vi.fn().mockRejectedValue(apiError(409, "PAYMENT_PATH_CONFLICT")) });
    expect(await startCheckoutFlow(1042, null, d)).toEqual({ kind: "unknown" });
  });

  it("쿠폰 거절 → again(같은 화면에서 다시), 사유 문구", async () => {
    const d = deps({
      get: sequence(
        checkout({ state: "COUPON_REJECTED", retryable: true, failure: { code: "COUPON_NOT_USABLE", reason: "EXPIRED" }, pollAfterMs: null }),
      ),
    });
    expect(await startCheckoutFlow(1042, "coupon-1", d)).toEqual({
      kind: "again",
      state: "COUPON_REJECTED",
      retryable: true,
      message: expect.stringContaining("기간이 지난 쿠폰"),
    });
    expect(d.open).not.toHaveBeenCalled();
  });

  it("결제창 취소가 확인되면 PAYMENT_RETRYABLE → again(재결제 가능)", async () => {
    const d = deps({
      open: vi.fn().mockResolvedValue({ kind: "closed", message: "사용자가 결제를 취소했어요" }),
      get: sequence(
        ready,
        checkout({ state: "PAYMENT_RETRYABLE", retryable: true, failure: { code: "PAYMENT_DECLINED", reason: "USER_CANCELED" }, pollAfterMs: null }),
      ),
    });
    expect(await startCheckoutFlow(1042, null, d)).toMatchObject({ kind: "again", state: "PAYMENT_RETRYABLE", retryable: true });
    // 창을 닫아도 확인 요청은 보낸다 — 결과는 서버가 포트원에서 정한다
    expect(d.confirm).toHaveBeenCalledTimes(1);
  });

  it("결제 거절(FAILED + PAYMENT_DECLINED)은 사유 코드, 단계 시간 초과는 문구", async () => {
    const declined = deps({
      get: sequence(checkout({ state: "FAILED", failure: { code: "PAYMENT_DECLINED", reason: "PG_DECLINED" }, pollAfterMs: null })),
    });
    expect(await startCheckoutFlow(1042, null, declined)).toEqual({ kind: "declined", failureCode: "PG_DECLINED" });

    const timeout = deps({
      get: sequence(checkout({ state: "FAILED", failure: { code: "STEP_TIMEOUT", reason: "PAYMENT_PREPARING" }, pollAfterMs: null })),
    });
    expect(await startCheckoutFlow(1042, null, timeout)).toMatchObject({
      kind: "declined",
      failureCode: null,
      message: expect.stringContaining("돈은 나가지 않았어요"),
    });
  });

  it("만료는 환불 안내(refundNotice)를 그대로 넘긴다", async () => {
    const d = deps({
      get: sequence(checkout({ state: "EXPIRED", refundNotice: "WILL_REFUND_IF_PAID", failure: { code: "HOLD_EXPIRED", reason: "PAYMENT_RESULT_UNKNOWN" }, pollAfterMs: null })),
    });
    expect(await startCheckoutFlow(1042, null, d)).toEqual({ kind: "expired", refunded: false, refundNotice: "WILL_REFUND_IF_PAID" });
  });

  it("30초 안에 끝나지 않으면 unknown(결과 확인 중) — 조회 실패는 삼키고 계속 본다", async () => {
    const d = deps({ get: sequence(new NetworkError(new TypeError("Failed to fetch")), checkout({ state: "VERIFYING" })) });
    expect(await startCheckoutFlow(1042, null, d)).toEqual({ kind: "unknown" });
    expect(d.clock.t).toBeGreaterThanOrEqual(CHECKOUT_POLL_LIMIT_MS);
  });

  it("결제창을 닫고 30초 안에 결과가 안 나오면 windowClosed", async () => {
    const d = deps({
      open: vi.fn().mockResolvedValue({ kind: "closed", message: "결제창을 닫았어요" }),
      get: sequence(ready, verifying),
    });
    expect(await startCheckoutFlow(1042, null, d)).toEqual({ kind: "windowClosed", message: "결제창을 닫았어요" });
  });

  it("결제창이 페이지를 떠나면(모바일 리다이렉트) 확인 요청 없이 끝낸다", async () => {
    const d = deps({ open: vi.fn().mockResolvedValue({ kind: "redirected" }), get: sequence(ready) });
    expect(await startCheckoutFlow(1042, null, d)).toEqual({ kind: "unknown" });
    expect(d.confirm).not.toHaveBeenCalled();
  });

  it("확인 요청 응답을 못 받으면 같은 키로 한 번만 다시 보낸다", async () => {
    const confirm = vi.fn().mockRejectedValueOnce(new NetworkError(new TypeError("Failed to fetch"))).mockResolvedValue({ ...accepted, state: "VERIFYING" });
    const d = deps({ confirm });
    await startCheckoutFlow(1042, null, d);
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(confirm.mock.calls[0][2]).toBe(confirm.mock.calls[1][2]);
  });
});

describe("completeCheckoutFlow · resumeCheckoutFlow", () => {
  it("모바일 결제창이 돌아오면 확인 요청 후 polling — 같은 결제로 창을 다시 열지 않는다", async () => {
    const d = deps({ get: sequence(ready, confirmed) });
    expect(await completeCheckoutFlow(1042, PAYMENT_ID, null, d)).toMatchObject({ kind: "confirmed" });
    expect(d.confirm).toHaveBeenCalledWith(1042, PAYMENT_ID, "key-1");
    expect(d.open).not.toHaveBeenCalled();
  });

  it("확인 요청이 409(이미 확인 중)여도 polling으로 결과를 본다", async () => {
    const d = deps({ confirm: vi.fn().mockRejectedValue(apiError(409, "CHECKOUT_NOT_READY")), get: sequence(confirmed) });
    expect(await completeCheckoutFlow(1042, PAYMENT_ID, null, d)).toMatchObject({ kind: "confirmed" });
    expect(d.confirm).toHaveBeenCalledTimes(1);
  });

  it("새로고침은 시작하지 않고 이어서 본다", async () => {
    const d = deps({ get: sequence(verifying, confirmed) });
    expect(await resumeCheckoutFlow(1042, d)).toMatchObject({ kind: "confirmed" });
    expect(d.start).not.toHaveBeenCalled();
  });
});
