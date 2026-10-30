import { describe, expect, it } from "vitest";
import type { AuditoriumLayout, LayoutSeat, SeatRenderState } from "../model/types";
import {
  MAP_PADDING,
  countSeatStates,
  isSelectable,
  layoutViewBox,
  rowMarkers,
  seatAriaLabel,
  TARGET_SEAT_PX,
  seatFontSize,
  toPixelLayout,
  viewBoxAttr,
} from "./seat-map";

const seat = (over: Partial<LayoutSeat> & { seatId: number }): LayoutSeat => ({
  seatLabel: `A${over.seatId}`,
  rowLabel: "A",
  colNo: 1,
  x: 0,
  y: 0,
  w: 20,
  h: 20,
  seatType: "NORMAL",
  active: true,
  ...over,
});

const layout = (over: Partial<AuditoriumLayout> = {}): AuditoriumLayout => ({
  auditoriumId: 4060,
  name: "1관",
  screenType: "NORMAL",
  layoutVersion: 1,
  width: 200,
  height: 100,
  screen: { xFrom: 20, xTo: 180 },
  entrances: [],
  verticalAisles: [],
  horizontalAisles: [],
  seats: [],
  source: { provider: "LOTTE", fetchedAt: "2026-10-14T12:00:00+09:00" },
  ...over,
});

describe("layoutViewBox", () => {
  it("좌석 좌표계 바깥으로 여백을 붙인다", () => {
    const box = layoutViewBox(layout());
    expect(box.minX).toBe(-MAP_PADDING.left);
    expect(box.minY).toBe(-MAP_PADDING.top);
    expect(box.width).toBe(200 + MAP_PADDING.left + MAP_PADDING.right);
    expect(box.height).toBe(100 + MAP_PADDING.top + MAP_PADDING.bottom);
  });

  it("좌석이 width·height를 넘으면 좌석 범위까지 넓힌다 (끝 좌석이 잘리지 않게)", () => {
    const box = layoutViewBox(
      layout({
        width: 100,
        height: 50,
        screen: { xFrom: 10, xTo: 90 },
        seats: [seat({ seatId: 1, x: 90, y: 40, w: 30, h: 30 })],
      }),
    );
    expect(box.width).toBe(120 + MAP_PADDING.left + MAP_PADDING.right);
    expect(box.height).toBe(70 + MAP_PADDING.top + MAP_PADDING.bottom);
  });

  it("스크린이 좌석보다 넓으면 스크린까지 들어가게 넓힌다", () => {
    const box = layoutViewBox(
      layout({ width: 100, screen: { xFrom: 0, xTo: 150 }, seats: [seat({ seatId: 1, x: 0, w: 20 })] }),
    );
    expect(box.width).toBe(150 + MAP_PADDING.left + MAP_PADDING.right);
  });

  it("viewBox 문자열은 네 값을 공백으로 잇는다", () => {
    expect(viewBoxAttr({ minX: -1, minY: -2, width: 3, height: 4 })).toBe("-1 -2 3 4");
  });
});

describe("rowMarkers", () => {
  it("줄마다 하나씩, 가운데 y 순서로 돌려준다", () => {
    const markers = rowMarkers([
      seat({ seatId: 1, rowLabel: "B", y: 40 }),
      seat({ seatId: 2, rowLabel: "A", y: 0 }),
      seat({ seatId: 3, rowLabel: "A", y: 0 }),
    ]);
    expect(markers.map((marker) => marker.rowLabel)).toEqual(["A", "B"]);
    expect(markers[0].centerY).toBe(10);
    expect(markers[1].centerY).toBe(50);
  });

  it("같은 줄의 좌석 높이가 다르면 가장 큰 높이를 글자 기준으로 쓴다", () => {
    const [marker] = rowMarkers([seat({ seatId: 1, h: 20 }), seat({ seatId: 2, h: 34 })]);
    expect(marker.height).toBe(34);
  });

  it("좌석이 없으면 빈 배열", () => {
    expect(rowMarkers([])).toEqual([]);
  });
});

describe("좌석 상태", () => {
  it("고를 수 있는 것은 선택 가능·선택함뿐", () => {
    const states: SeatRenderState[] = ["available", "selected", "held", "reserved", "inactive"];
    expect(states.filter(isSelectable)).toEqual(["available", "selected"]);
  });

  it("읽어 주는 설명에 좌석 이름과 상태가 함께 들어간다", () => {
    expect(seatAriaLabel(seat({ seatId: 1, seatLabel: "J7" }), "held")).toBe("J7 다른 사람이 선점 중");
  });

  it("상태별 좌석 수를 센다", () => {
    const seats = [seat({ seatId: 1 }), seat({ seatId: 2 }), seat({ seatId: 3, active: false })];
    const counts = countSeatStates(seats, (target) => (target.active ? "available" : "inactive"));
    expect(counts).toEqual({ available: 2, selected: 0, held: 0, reserved: 0, inactive: 1 });
  });
});

describe("toPixelLayout", () => {
  // 실제 CAT-04 좌표: 좌석 한 칸 0.8, 상영관 폭 14.7 (건대입구 1관)
  const real = layout({
    width: 14.7,
    height: 8.8,
    screen: { xFrom: 0, xTo: 14.7 },
    entrances: [{ x: 14, y: 8 }],
    verticalAisles: [{ xFrom: 3, xTo: 4 }],
    horizontalAisles: [{ yFrom: 5, yTo: 5.5 }],
    seats: [
      seat({ seatId: 1, x: 0, y: 0, w: 0.8, h: 0.8 }),
      seat({ seatId: 2, x: 1, y: 0, w: 0.8, h: 0.8 }),
      seat({ seatId: 3, x: 2, y: 0, w: 1.6, h: 0.8, seatType: "RECLINER" }),
    ],
  });

  it("좌석 크기 중앙값이 목표 px가 되도록 모든 좌표를 같은 배율로 키운다", () => {
    const px = toPixelLayout(real);
    const k = TARGET_SEAT_PX / 0.8;
    expect(px.seats[0].w).toBeCloseTo(TARGET_SEAT_PX);
    expect(px.seats[1].x).toBeCloseTo(1 * k);
    expect(px.width).toBeCloseTo(14.7 * k);
    expect(px.screen.xTo).toBeCloseTo(14.7 * k);
    expect(px.entrances[0]).toEqual({ x: expect.closeTo(14 * k), y: expect.closeTo(8 * k) });
    expect(px.verticalAisles[0].xTo).toBeCloseTo(4 * k);
    expect(px.horizontalAisles[0].yFrom).toBeCloseTo(5 * k);
  });

  it("큰 좌석(리클라이너)은 비율 그대로 크게 남는다", () => {
    expect(toPixelLayout(real).seats[2].w).toBeCloseTo(TARGET_SEAT_PX * 2);
  });

  it("좌석이 없으면 그대로 돌려준다", () => {
    const empty = layout();
    expect(toPixelLayout(empty)).toBe(empty);
  });
});

describe("seatFontSize", () => {
  it("높이의 38%를 넘지 않는다", () => {
    expect(seatFontSize(seat({ seatId: 1, seatLabel: "A1", w: 34, h: 34 }))).toBeCloseTo(34 * 0.38);
  });

  it("이름이 길면 폭에 맞춰 줄인다", () => {
    const short = seatFontSize(seat({ seatId: 1, seatLabel: "A1", w: 34, h: 34 }));
    const long = seatFontSize(seat({ seatId: 1, seatLabel: "AA12", w: 34, h: 34 }));
    expect(long).toBeLessThan(short);
    expect(long * 0.62 * 4).toBeLessThanOrEqual(34 * 0.86 + 1e-9);   // 네 글자가 좌석 폭 안에 들어간다
  });
});
