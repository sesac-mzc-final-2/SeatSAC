import { describe, expect, it } from "vitest";
import { remainingSeatsLabel, screenTypeLabel } from "./labels";

describe("labels", () => {
  it("BOOKING 집계: 잔여 / 전체, 10% 이하면 low, 0이면 매진·선택 불가", () => {
    expect(remainingSeatsLabel({ remainingSeats: 171, totalSeats: 184, remainingSeatsSource: "BOOKING" })).toEqual({
      text: "171 / 184",
      tone: "ok",
      selectable: true,
    });
    expect(remainingSeatsLabel({ remainingSeats: 10, totalSeats: 100, remainingSeatsSource: "BOOKING" }).tone).toBe("low");
    expect(remainingSeatsLabel({ remainingSeats: 0, totalSeats: 100, remainingSeatsSource: "BOOKING" })).toEqual({
      text: "매진",
      tone: "soldout",
      selectable: false,
    });
  });

  it("TOTAL(집계 지연): 전체 좌석 수만, 매진 판단 없음", () => {
    expect(remainingSeatsLabel({ remainingSeats: 184, totalSeats: 184, remainingSeatsSource: "TOTAL" })).toEqual({
      text: "184석 (집계 지연)",
      tone: "unknown",
      selectable: true,
    });
  });

  it("상영관 종류", () => {
    expect(screenTypeLabel("NORMAL")).toBe("일반");
    expect(screenTypeLabel("RECLINER")).toBe("리클라이너");
  });
});
