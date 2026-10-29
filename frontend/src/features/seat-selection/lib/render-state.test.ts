import { describe, expect, it } from "vitest";
import { seatRenderState } from "./render-state";

describe("seatRenderState", () => {
  it("판매하지 않는 자리가 가장 우선", () => {
    expect(seatRenderState({ status: "AVAILABLE", active: false, selected: true })).toBe("inactive");
  });

  it("내가 고른 좌석은 서버가 HELD라고 해도 「선택함」으로 그린다", () => {
    expect(seatRenderState({ status: "HELD", active: true, selected: true })).toBe("selected");
  });

  it("내 예매가 잡고 있는 좌석도 「선택함」", () => {
    expect(seatRenderState({ status: "HELD", active: true, selected: false, mine: true })).toBe("selected");
  });

  it("남이 잡은 좌석과 예매 완료 좌석을 구분한다", () => {
    expect(seatRenderState({ status: "HELD", active: true, selected: false })).toBe("held");
    expect(seatRenderState({ status: "RESERVED", active: true, selected: false })).toBe("reserved");
  });

  it("그 밖에는 고를 수 있다", () => {
    expect(seatRenderState({ status: "AVAILABLE", active: true, selected: false })).toBe("available");
  });
});
