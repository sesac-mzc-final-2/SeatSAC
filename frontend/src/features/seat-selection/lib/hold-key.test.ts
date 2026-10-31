import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "@/shared/api";
import { holdRequestSignature, shouldReuseHoldKey } from "./hold-key";

const apiError = (status: number, code: string) =>
  new ApiError(status, { code, message: "", traceId: "0".repeat(32), details: [] });

describe("shouldReuseHoldKey", () => {
  it("결과를 모르면 같은 키를 쓴다 — 응답 없음·5xx·처리 중", () => {
    expect(shouldReuseHoldKey(new NetworkError(new TypeError("Failed to fetch")))).toBe(true);
    expect(shouldReuseHoldKey(apiError(503, "SERVICE_UNAVAILABLE"))).toBe(true);
    expect(shouldReuseHoldKey(apiError(409, "IDEMPOTENCY_IN_PROGRESS"))).toBe(true);
  });

  it("서버가 저장한 거절(4xx)이면 새 키 — 같은 키로는 좌석이 풀려도 같은 거절만 돌아온다", () => {
    expect(shouldReuseHoldKey(apiError(409, "SEAT_UNAVAILABLE"))).toBe(false);
    expect(shouldReuseHoldKey(apiError(409, "ACTIVE_HOLD_EXISTS"))).toBe(false);
    expect(shouldReuseHoldKey(apiError(400, "SCREENING_STARTED"))).toBe(false);
    expect(shouldReuseHoldKey(new Error("other"))).toBe(false);
  });
});

describe("holdRequestSignature", () => {
  it("좌석 순서가 다르면 다른 요청이다 (서버가 본문 해시로 비교한다)", () => {
    expect(holdRequestSignature({ screeningId: 1, seatIds: [1, 2] })).not.toBe(
      holdRequestSignature({ screeningId: 1, seatIds: [2, 1] }),
    );
    expect(holdRequestSignature({ screeningId: 1, seatIds: [1, 2] })).toBe(
      holdRequestSignature({ screeningId: 1, seatIds: [1, 2] }),
    );
  });
});
