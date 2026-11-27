import { describe, expect, it } from "vitest";
import { isKstInput, kstInputToIso, nextMinuteKstInput } from "./kst-input";

describe("쿠폰 이벤트 시작 시각(KST 입력)", () => {
  it("지금 이후 가장 가까운 정분을 KST로 — 브라우저 시간대와 상관없이", () => {
    // 2026-11-27T04:59:30Z = KST 13:59:30 → 14:00
    expect(nextMinuteKstInput(Date.parse("2026-11-27T04:59:30Z"))).toBe("2026-11-27T14:00");
    // 정분이면 그대로
    expect(nextMinuteKstInput(Date.parse("2026-11-27T05:00:00Z"))).toBe("2026-11-27T14:00");
    // 자정 넘김(KST 23:59:10 → 다음 날 00:00)
    expect(nextMinuteKstInput(Date.parse("2026-11-27T14:59:10Z"))).toBe("2026-11-28T00:00");
  });

  it("입력 값을 +09:00 ISO로", () => {
    expect(kstInputToIso("2026-11-27T14:00")).toBe("2026-11-27T14:00:00+09:00");
  });

  it("초가 붙은 입력(HH:mm:ss)도 분까지만 쓴다", () => {
    expect(kstInputToIso("2026-11-27T14:00:30")).toBe("2026-11-27T14:00:00+09:00");
    expect(kstInputToIso("2026-11-27T14:00:30.000")).toBe("2026-11-27T14:00:00+09:00");
  });

  it("입력 모양 검사", () => {
    expect(isKstInput("2026-11-27T14:00")).toBe(true);
    expect(isKstInput("2026-11-27T14:00:30")).toBe(true);
    expect(isKstInput("")).toBe(false);
    expect(isKstInput("2026-11-27")).toBe(false);
    expect(isKstInput("14:00")).toBe(false);
  });
});
