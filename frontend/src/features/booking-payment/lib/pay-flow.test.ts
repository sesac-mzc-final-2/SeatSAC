import { describe, expect, it, vi } from "vitest";
import type { Booking, PayRequest, PayResult } from "@/entities/booking";
import { ApiError, NetworkError } from "@/shared/api";
import { POLL_ATTEMPTS, settlePayment, type PayFlowDeps } from "./pay-flow";

const request: PayRequest = {
  method: "CARD",
  card: { number: "4242424242424242", expiry: "12/28", holder: "KIM" },
};

const approved: PayResult = {
  bookingId: 9876,
  status: "CONFIRMED",
  confirmedAt: "2026-11-16T14:04:10+09:00",
  seatLabels: ["J7", "J8"],
  payment: { paymentId: "p-1", status: "APPROVED", cardLast4: "4242" },
};

const declined: PayResult = {
  bookingId: 9876,
  status: "PAYMENT_FAILED",
  payment: { paymentId: "p-1", status: "FAILED", failureCode: "CARD_DECLINED" },
};

const booking = (over: Partial<Booking> = {}): Booking => ({
  bookingId: 9876,
  screeningId: 5123,
  status: "PENDING",
  seatIds: [1, 2],
  seatLabels: ["J7", "J8"],
  seatCount: 2,
  amount: 30000,
  expiresAt: "2026-11-16T14:08:05+09:00",
  createdAt: "2026-11-16T14:03:05+09:00",
  confirmedAt: null,
  canceledAt: null,
  payment: null,
  ...over,
});

const apiError = (status: number, code: string, details: { field: string; value?: unknown }[] = []) =>
  new ApiError(status, { code, message: "개발용", traceId: "a".repeat(32), details });

function deps(over: Partial<PayFlowDeps> = {}): PayFlowDeps {
  return {
    pay: vi.fn().mockResolvedValue(approved),
    load: vi.fn().mockResolvedValue(booking()),
    wait: vi.fn().mockResolvedValue(undefined),
    newKey: () => "key-1",
    ...over,
  };
}

describe("settlePayment", () => {
  it("승인되면 좌석 이름과 함께 확정으로 끝난다", async () => {
    const outcome = await settlePayment(9876, request, deps());
    expect(outcome).toEqual({
      kind: "confirmed",
      bookingId: 9876,
      seatLabels: ["J7", "J8"],
      confirmedAt: "2026-11-16T14:04:10+09:00",
    });
  });

  it("거절도 200이라 오류가 아니라 declined로 끝난다", async () => {
    const outcome = await settlePayment(9876, request, deps({ pay: vi.fn().mockResolvedValue(declined) }));
    expect(outcome).toEqual({ kind: "declined", failureCode: "CARD_DECLINED" });
  });

  it("요청마다 새 멱등 키를 쓴다", async () => {
    const pay = vi.fn().mockResolvedValue(approved);
    await settlePayment(9876, request, deps({ pay, newKey: () => "key-abc" }));
    expect(pay).toHaveBeenCalledWith(9876, request, "key-abc");
  });

  describe("503 PAYMENT_RESULT_UNKNOWN", () => {
    it("2초 기다린 뒤 조회해서 확정을 확인한다", async () => {
      const wait = vi.fn().mockResolvedValue(undefined);
      const load = vi
        .fn()
        .mockResolvedValue(booking({ status: "CONFIRMED", confirmedAt: "2026-11-16T14:04:10+09:00" }));
      const outcome = await settlePayment(
        9876,
        request,
        deps({ pay: vi.fn().mockRejectedValue(apiError(503, "PAYMENT_RESULT_UNKNOWN")), wait, load }),
      );
      expect(wait).toHaveBeenCalledWith(2000);
      expect(load).toHaveBeenCalledTimes(1);
      expect(outcome).toMatchObject({ kind: "confirmed", bookingId: 9876 });
    });

    it("PENDING이면 최대 5회까지 다시 확인한다", async () => {
      const load = vi.fn().mockResolvedValue(booking({ status: "PENDING" }));
      const onChecking = vi.fn();
      const outcome = await settlePayment(
        9876,
        request,
        deps({ pay: vi.fn().mockRejectedValue(apiError(503, "PAYMENT_RESULT_UNKNOWN")), load, onChecking }),
      );
      expect(load).toHaveBeenCalledTimes(POLL_ATTEMPTS);
      expect(onChecking).toHaveBeenLastCalledWith(POLL_ATTEMPTS);
      expect(outcome).toEqual({ kind: "unknown" });
    });

    it("확인 중간에 결과가 나오면 바로 멈춘다", async () => {
      const load = vi
        .fn()
        .mockResolvedValueOnce(booking({ status: "PENDING" }))
        .mockResolvedValueOnce(
          booking({
            status: "PAYMENT_FAILED",
            payment: { paymentId: "p", status: "FAILED", failureCode: "INSUFFICIENT_BALANCE" },
          }),
        );
      const outcome = await settlePayment(
        9876,
        request,
        deps({ pay: vi.fn().mockRejectedValue(apiError(503, "PAYMENT_RESULT_UNKNOWN")), load }),
      );
      expect(load).toHaveBeenCalledTimes(2);
      expect(outcome).toEqual({ kind: "declined", failureCode: "INSUFFICIENT_BALANCE" });
    });

    it("확인하는 동안의 조회 오류는 넘기고 계속 본다", async () => {
      const load = vi
        .fn()
        .mockRejectedValueOnce(apiError(503, "SERVICE_UNAVAILABLE"))
        .mockResolvedValueOnce(booking({ status: "CONFIRMED" }));
      const outcome = await settlePayment(
        9876,
        request,
        deps({ pay: vi.fn().mockRejectedValue(apiError(503, "PAYMENT_RESULT_UNKNOWN")), load }),
      );
      expect(outcome).toMatchObject({ kind: "confirmed" });
    });
  });

  it("마지막 확인까지 조회가 실패하면 error가 아니라 unknown이다 (결제 폼을 다시 열지 않게)", async () => {
    const load = vi.fn().mockRejectedValue(apiError(503, "SERVICE_UNAVAILABLE"));
    const outcome = await settlePayment(
      9876,
      request,
      deps({ pay: vi.fn().mockRejectedValue(apiError(503, "PAYMENT_RESULT_UNKNOWN")), load }),
    );
    expect(load).toHaveBeenCalledTimes(POLL_ATTEMPTS);
    expect(outcome).toEqual({ kind: "unknown" });
  });

  it("응답을 못 받으면(NetworkError) 다시 결제하지 않고 조회로 결과를 맞춘다", async () => {
    const pay = vi.fn().mockRejectedValue(new NetworkError(new Error("fetch failed")));
    const wait = vi.fn().mockResolvedValue(undefined);
    const load = vi.fn().mockResolvedValue(booking({ status: "CONFIRMED" }));
    const outcome = await settlePayment(9876, request, deps({ pay, wait, load }));
    expect(pay).toHaveBeenCalledTimes(1);
    // 요청이 서버에 닿았는지 모르므로 2초 뒤부터 확인한다
    expect(wait).toHaveBeenCalledWith(2000);
    expect(outcome).toMatchObject({ kind: "confirmed" });
  });

  it("이미 결제를 요청한 예매면 다시 결제하지 않고 조회로 확인한다", async () => {
    const pay = vi.fn().mockRejectedValue(apiError(409, "PAYMENT_ALREADY_REQUESTED"));
    const wait = vi.fn().mockResolvedValue(undefined);
    const load = vi.fn().mockResolvedValue(booking({ status: "CONFIRMED" }));
    const outcome = await settlePayment(9876, request, deps({ pay, wait, load }));
    expect(pay).toHaveBeenCalledTimes(1);
    // 결과가 이미 났을 수 있어 첫 확인은 기다리지 않는다
    expect(wait).not.toHaveBeenCalled();
    expect(outcome).toMatchObject({ kind: "confirmed" });
  });

  it("승인 뒤 선점이 만료됐으면 환불 여부와 함께 알린다", async () => {
    const outcome = await settlePayment(
      9876,
      request,
      deps({
        pay: vi.fn().mockRejectedValue(
          apiError(409, "BOOKING_STATE_CONFLICT", [
            { field: "status", value: "EXPIRED" },
            { field: "refunded", value: true },
          ]),
        ),
      }),
    );
    expect(outcome).toEqual({ kind: "expired", refunded: true });
  });

  it("그 밖의 상태 충돌은 안내 문구로", async () => {
    const outcome = await settlePayment(
      9876,
      request,
      deps({
        pay: vi
          .fn()
          .mockRejectedValue(apiError(409, "BOOKING_STATE_CONFLICT", [{ field: "status", value: "CANCELED" }])),
      }),
    );
    expect(outcome).toMatchObject({ kind: "error" });
  });

  it("알 수 없는 예외는 error로 (응답을 받은 적이 없는 NetworkError와 구분)", async () => {
    const outcome = await settlePayment(9876, request, deps({ pay: vi.fn().mockRejectedValue(new Error("boom")) }));
    expect(outcome).toMatchObject({ kind: "error" });
  });
});
