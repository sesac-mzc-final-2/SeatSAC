import { describe, expect, it } from "vitest";
import { ApiError } from "@/shared/api";
import { isAlreadyReleased } from "./use-release-hold";

const conflict = (status: string, reason: string) =>
  new ApiError(409, {
    code: "BOOKING_STATE_CONFLICT",
    message: "",
    traceId: "0".repeat(32),
    details: [{ field: "status", value: status, reason }],
  });

describe("isAlreadyReleased", () => {
  it("만료·다른 창에서 해제·결제 거절이면 좌석이 이미 풀렸다", () => {
    expect(isAlreadyReleased(conflict("EXPIRED", "BOOKING_NOT_PENDING"))).toBe(true);
    expect(isAlreadyReleased(conflict("CANCELED", "BOOKING_NOT_PENDING"))).toBe(true);
    expect(isAlreadyReleased(conflict("PAYMENT_FAILED", "BOOKING_NOT_PENDING"))).toBe(true);
  });

  it("결제 진행 중·확정은 좌석이 아직 잡혀 있어 오류로 둔다", () => {
    expect(isAlreadyReleased(conflict("PENDING", "PAYMENT_IN_PROGRESS"))).toBe(false);
    expect(isAlreadyReleased(conflict("CONFIRMED", "BOOKING_NOT_PENDING"))).toBe(false);
    expect(isAlreadyReleased(new Error("network"))).toBe(false);
  });
});
