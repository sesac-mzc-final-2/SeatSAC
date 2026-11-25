import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "@/shared/api";
import { claimOutcomeOf } from "./claim-outcome";

const apiError = (status: number, code: string, details: { field: string; reason?: string }[] = []) =>
  new ApiError(status, { code, message: "", traceId: "0".repeat(32), details });

describe("claimOutcomeOf", () => {
  it("이미 받음은 받은 것과 같게", () => {
    expect(claimOutcomeOf(apiError(409, "COUPON_ALREADY_CLAIMED"))).toEqual({ kind: "issued", already: true });
  });

  it("결과를 모르면(503·연결 끊김·처리 중) 같은 키로 재시도", () => {
    expect(claimOutcomeOf(apiError(503, "SERVICE_UNAVAILABLE")).kind).toBe("retry");
    expect(claimOutcomeOf(new NetworkError(new TypeError("fetch failed"))).kind).toBe("retry");
    expect(claimOutcomeOf(apiError(409, "IDEMPOTENCY_IN_PROGRESS")).kind).toBe("retry");
  });

  it("거절은 사유별 문구, 다음은 새 키", () => {
    expect(claimOutcomeOf(apiError(403, "COUPON_NOT_ELIGIBLE"))).toMatchObject({ kind: "rejected" });
    expect(claimOutcomeOf(apiError(409, "COUPON_SOLD_OUT"))).toEqual({
      kind: "rejected",
      message: "아쉽지만 선착순 쿠폰이 모두 소진됐어요.",
    });
    expect(
      claimOutcomeOf(apiError(409, "COUPON_EVENT_NOT_OPEN", [{ field: "eventId", reason: "CLOSED" }])),
    ).toEqual({ kind: "rejected", message: "이벤트가 끝났어요." });
    expect(
      claimOutcomeOf(apiError(409, "COUPON_EVENT_NOT_OPEN", [{ field: "eventId", reason: "UPCOMING" }])),
    ).toMatchObject({ kind: "rejected", message: expect.stringContaining("시작 전") });
  });
});
