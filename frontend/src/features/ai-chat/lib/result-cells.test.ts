import { describe, expect, it } from "vitest";
import { columnLabel, formatCell } from "./result-cells";

describe("result-cells", () => {
  it("아는 열은 한국어 머리글, 모르는 열은 그대로", () => {
    expect(columnLabel({ name: "movie_title" })).toBe("영화");
    expect(columnLabel({ name: "avg_seats" })).toBe("avg_seats");
  });

  it("KST 분 단위 시각은 월/일 시:분으로", () => {
    expect(formatCell({ name: "starts_at", type: "text" }, "2026-11-17 20:10")).toBe("11/17 20:10");
  });

  it("금액·비율·숫자·null", () => {
    expect(formatCell({ name: "amount", type: "integer" }, 30000)).toBe("30,000원");
    expect(formatCell({ name: "remaining_ratio", type: "real" }, 0.065)).toBe("6.5%");
    expect(formatCell({ name: "remaining_seats", type: "integer" }, 1210)).toBe("1,210");
    expect(formatCell({ name: "director", type: "text" }, null)).toBe("–");
  });

  it("예매 상태·상영관 종류 코드는 화면 문구로", () => {
    expect(formatCell({ name: "status", type: "text" }, "CONFIRMED")).toBe("예매 완료");
    expect(formatCell({ name: "screen_type", type: "text" }, "RECLINER")).toBe("리클라이너");
  });
});
