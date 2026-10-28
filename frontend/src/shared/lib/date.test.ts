import { describe, expect, it } from "vitest";
import { addDays, dayOfWeekKo, formatDateKo, isValidIsoDate, isWeekend, timeOfIso, todayKst } from "./date";

describe("date (KST)", () => {
  it("todayKst는 UTC 자정 전이라도 서울 날짜를 돌려준다", () => {
    // 2026-10-26 16:00Z = 2026-10-27 01:00 KST
    expect(todayKst(new Date("2026-10-26T16:00:00Z"))).toBe("2026-10-27");
    expect(todayKst(new Date("2026-10-26T14:59:00Z"))).toBe("2026-10-26");
  });

  it("addDays는 월·연도를 넘긴다", () => {
    expect(addDays("2026-10-31", 1)).toBe("2026-11-01");
    expect(addDays("2026-12-30", 3)).toBe("2027-01-02");
  });

  it("요일·주말", () => {
    expect(dayOfWeekKo("2026-11-16")).toBe("월");
    expect(isWeekend("2026-11-15")).toBe(true);
    expect(isWeekend("2026-11-16")).toBe(false);
    expect(formatDateKo("2026-11-16")).toBe("11월 16일 (월)");
  });

  it("timeOfIso는 시간대 변환 없이 HH:mm만 꺼낸다", () => {
    expect(timeOfIso("2026-11-16T14:30:00+09:00")).toBe("14:30");
    expect(timeOfIso("2026-11-16T00:05:00+09:00")).toBe("00:05");
  });

  it("isValidIsoDate", () => {
    expect(isValidIsoDate("2026-02-29")).toBe(false);
    expect(isValidIsoDate("2028-02-29")).toBe(true);
    expect(isValidIsoDate("20261116")).toBe(false);
    expect(isValidIsoDate(null)).toBe(false);
  });
});
