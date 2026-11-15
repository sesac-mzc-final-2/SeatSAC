import { describe, expect, it, vi } from "vitest";
import type { Booking, PaymentPrepareResult, PayResult } from "@/entities/booking";
import { ApiError, NetworkError } from "@/shared/api";
import { readTossPayReturn } from "./portone";
import { completeTossPay, startTossPay, type TossPayFlowDeps } from "./tosspay-flow";

const prepared: PaymentPrepareResult = {
  bookingId: 9876,
  paymentId: "3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13",
  amount: 30000,
  orderName: "SeatSAC 영화 예매 2석",
  expiresAt: "2026-11-16T14:08:05+09:00",
  checkout: { provider: "PORTONE", storeId: "store-test", channelKey: "channel-test", payMethod: "EASY_PAY", currency: "KRW" },
};

const confirmed: PayResult = {
  bookingId: 9876,
  status: "CONFIRMED",
  confirmedAt: "2026-11-16T14:04:10+09:00",
  seatLabels: ["J7", "J8"],
  payment: { paymentId: prepared.paymentId, status: "APPROVED", method: "TOSSPAY", cardLast4: null },
};

const pending: Booking = {
  bookingId: 9876,
  screeningId: 5123,
  status: "PENDING",
  seatIds: [1, 2],
  seatLabels: ["J7", "J8"],
  seatCount: 2,
  amount: 30000,
  expiresAt: "2026-11-16T14:08:05+09:00",
  createdAt: "2026-11-16T14:03:05+09:00",
  payment: { paymentId: prepared.paymentId, status: "PROCESSING", method: "TOSSPAY" },
};

const apiError = (status: number, code: string, details: { field: string; value?: unknown; reason?: string }[] = []) =>
  new ApiError(status, { code, message: "개발용", traceId: "a".repeat(32), details });

function deps(over: Partial<TossPayFlowDeps> = {}): TossPayFlowDeps {
  let key = 0;
  return {
    prepare: vi.fn().mockResolvedValue(prepared),
    open: vi.fn().mockResolvedValue({ kind: "done" }),
    pay: vi.fn().mockResolvedValue(confirmed),
    load: vi.fn().mockResolvedValue(pending),
    wait: vi.fn().mockResolvedValue(undefined),
    newKey: () => `key-${++key}`,
    redirectUrl: "http://localhost:5173/bookings/9876/pay",
    ...over,
  };
}

describe("startTossPay", () => {
  it("준비 → 결제창 → 같은 paymentId로 완료 확인, 준비와 완료는 다른 멱등 키", async () => {
    const d = deps();
    const outcome = await startTossPay(9876, d);

    expect(outcome).toMatchObject({ kind: "confirmed", seatLabels: ["J7", "J8"] });
    expect(d.prepare).toHaveBeenCalledWith(9876, "key-1");
    expect(d.open).toHaveBeenCalledWith(prepared, d.redirectUrl);
    expect(d.pay).toHaveBeenCalledWith(9876, { method: "TOSSPAY", paymentId: prepared.paymentId }, "key-2");
  });

  it("결제창을 취소해도 완료 확인을 부른다 — 서버가 거절로 답하면 그 결과로 끝난다", async () => {
    const d = deps({
      open: vi.fn().mockResolvedValue({ kind: "closed", message: "결제창을 닫았어요" }),
      pay: vi.fn().mockResolvedValue({
        bookingId: 9876,
        status: "PAYMENT_FAILED",
        payment: { paymentId: prepared.paymentId, status: "FAILED", failureCode: "USER_CANCELED" },
      } satisfies PayResult),
    });

    expect(await startTossPay(9876, d)).toEqual({ kind: "declined", failureCode: "USER_CANCELED" });
    expect(d.pay).toHaveBeenCalledTimes(1);
  });

  it("결제창을 닫았고 서버도 결과를 모르면 windowClosed — 결제를 다시 시작하지 않는다", async () => {
    const d = deps({
      open: vi.fn().mockResolvedValue({ kind: "closed", message: "결제창을 닫았어요" }),
      pay: vi.fn().mockRejectedValue(apiError(503, "PAYMENT_RESULT_UNKNOWN")),
    });

    expect(await startTossPay(9876, d)).toEqual({ kind: "windowClosed", message: "결제창을 닫았어요" });
    expect(d.prepare).toHaveBeenCalledTimes(1);
    expect(d.pay).toHaveBeenCalledTimes(1);
  });

  it("마감된 회차는 결제창을 열지 않는다", async () => {
    const d = deps({ prepare: vi.fn().mockRejectedValue(apiError(400, "SCREENING_STARTED")) });

    expect(await startTossPay(9876, d)).toMatchObject({ kind: "error" });
    expect(d.open).not.toHaveBeenCalled();
    expect(d.pay).not.toHaveBeenCalled();
  });

  it("이미 결제를 시작한 예매·응답 유실은 결제창을 다시 열지 않고 결과 미확인", async () => {
    for (const error of [apiError(409, "PAYMENT_ALREADY_REQUESTED"), new NetworkError(new Error("끊김"))]) {
      const d = deps({ prepare: vi.fn().mockRejectedValue(error) });
      expect(await startTossPay(9876, d)).toEqual({ kind: "unknown" });
      expect(d.open).not.toHaveBeenCalled();
    }
  });

  it("선점이 만료돼 준비가 막히면 만료로 끝낸다", async () => {
    const d = deps({
      prepare: vi.fn().mockRejectedValue(
        apiError(409, "BOOKING_STATE_CONFLICT", [{ field: "status", value: "PENDING", reason: "HOLD_EXPIRED" }]),
      ),
    });
    expect(await startTossPay(9876, d)).toEqual({ kind: "expired", refunded: false });
  });

  it("모바일 리다이렉트로 페이지를 떠나면 여기서는 결과를 정하지 않는다", async () => {
    const d = deps({ open: vi.fn().mockResolvedValue({ kind: "redirected" }) });
    expect(await startTossPay(9876, d)).toEqual({ kind: "unknown" });
    expect(d.pay).not.toHaveBeenCalled();
  });
});

describe("completeTossPay (모바일 결제창 복귀)", () => {
  it("결제창이 성공으로 돌아왔는데 아직 결과를 모르면 폴링 뒤 unknown", async () => {
    const d = deps({ pay: vi.fn().mockRejectedValue(apiError(503, "PAYMENT_RESULT_UNKNOWN")) });
    expect(await completeTossPay(9876, prepared.paymentId, null, d)).toEqual({ kind: "unknown" });
  });
});

describe("readTossPayReturn", () => {
  it("paymentId가 있으면 결제창에서 돌아온 것, code가 있으면 닫힘 문구", () => {
    expect(readTossPayReturn("?paymentId=p-1&code=FAILURE_TYPE_PG&message=%EC%B7%A8%EC%86%8C")).toEqual({
      paymentId: "p-1",
      closedMessage: "취소",
    });
    expect(readTossPayReturn("?paymentId=p-1&txId=t-1")).toEqual({ paymentId: "p-1", closedMessage: null });
    expect(readTossPayReturn("")).toBeNull();
  });
});
