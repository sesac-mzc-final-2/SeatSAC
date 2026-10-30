import type { AuditoriumLayout, LayoutSeat, SeatRenderState } from "../model/types";

/**
 * 배치도 바깥 여백 (화면 px). 왼쪽은 줄 이름, 위는 스크린, 아래는 입구 글자가 들어간다.
 * 좌석 좌표는 {@link toPixelLayout}으로 px로 바꾼 뒤에 쓰므로 여백·장식도 px로 둔다.
 */
export const MAP_PADDING = { top: 56, right: 16, bottom: 28, left: 34 } as const;

/**
 * 좌석 한 칸의 목표 크기(px, 짧은 변 기준). 「A12」처럼 좌석 이름 전체를 넣고 손가락으로도
 * 누를 수 있는 크기다.
 */
export const TARGET_SEAT_PX = 34;

/**
 * 수집 좌표를 화면 px로 바꾼다.
 *
 * <p>CAT-04 좌표는 상영관마다 단위가 정해져 있지 않다(실제 데이터는 좌석 한 칸이 0.7~0.8, 상영관 폭이
 * 15~26). 그대로 쓰면 px로 정한 여백·스크린·입구·글자가 좌석보다 몇십 배 커서 좌석이 점처럼 보인다.
 * 좌석 크기의 <b>중앙값</b>을 {@link TARGET_SEAT_PX}에 맞추는 배율 하나로 모든 좌표를 키운다 —
 * 비율이 같아 상영관 모양은 그대로이고, 리클라이너처럼 큰 좌석은 크게 남는다.
 */
export function toPixelLayout(layout: AuditoriumLayout): AuditoriumLayout {
  const sizes = layout.seats.map((seat) => Math.min(seat.w, seat.h)).filter((size) => size > 0);
  if (sizes.length === 0) return layout;
  const sorted = [...sizes].sort((a, b) => a - b);
  const median = sorted[Math.floor(sorted.length / 2)];
  const k = TARGET_SEAT_PX / median;
  return {
    ...layout,
    width: layout.width * k,
    height: layout.height * k,
    screen: { xFrom: layout.screen.xFrom * k, xTo: layout.screen.xTo * k },
    entrances: layout.entrances.map((entrance) => ({ x: entrance.x * k, y: entrance.y * k })),
    verticalAisles: layout.verticalAisles.map((aisle) => ({ xFrom: aisle.xFrom * k, xTo: aisle.xTo * k })),
    horizontalAisles: layout.horizontalAisles.map((aisle) => ({ yFrom: aisle.yFrom * k, yTo: aisle.yTo * k })),
    seats: layout.seats.map((seat) => ({ ...seat, x: seat.x * k, y: seat.y * k, w: seat.w * k, h: seat.h * k })),
  };
}

export interface ViewBox {
  minX: number;
  minY: number;
  width: number;
  height: number;
}

/**
 * 좌석 좌표계(0,0 ~ width,height) 바깥으로 여백을 붙인 viewBox.
 *
 * 수집한 좌표에는 좌석이 `width`를 살짝 넘는 경우가 있어(끝 좌석의 w 포함) 좌석 범위도 함께 본다 —
 * 안 보면 오른쪽 좌석이 잘린다.
 */
export function layoutViewBox(layout: AuditoriumLayout): ViewBox {
  const seatRight = layout.seats.reduce((max, seat) => Math.max(max, seat.x + seat.w), 0);
  const seatBottom = layout.seats.reduce((max, seat) => Math.max(max, seat.y + seat.h), 0);
  const contentWidth = Math.max(layout.width, seatRight, layout.screen.xTo);
  const contentHeight = Math.max(layout.height, seatBottom);
  return {
    minX: -MAP_PADDING.left,
    minY: -MAP_PADDING.top,
    width: contentWidth + MAP_PADDING.left + MAP_PADDING.right,
    height: contentHeight + MAP_PADDING.top + MAP_PADDING.bottom,
  };
}

export const viewBoxAttr = (box: ViewBox): string => `${box.minX} ${box.minY} ${box.width} ${box.height}`;

export interface RowMarker {
  rowLabel: string;
  /** 그 줄 좌석들의 가운데 y */
  centerY: number;
  /** 글자 크기 기준이 되는 좌석 높이 */
  height: number;
}

/** 왼쪽에 붙일 줄 이름. 좌석 안에도 이름을 적지만, 줄을 빨리 찾게 왼쪽에 한 번 더 둔다 */
export function rowMarkers(seats: readonly LayoutSeat[]): RowMarker[] {
  const rows = new Map<string, { top: number; bottom: number; height: number }>();
  for (const seat of seats) {
    const row = rows.get(seat.rowLabel);
    if (row) {
      row.top = Math.min(row.top, seat.y);
      row.bottom = Math.max(row.bottom, seat.y + seat.h);
      row.height = Math.max(row.height, seat.h);
    } else {
      rows.set(seat.rowLabel, { top: seat.y, bottom: seat.y + seat.h, height: seat.h });
    }
  }
  return [...rows.entries()]
    .map(([rowLabel, { top, bottom, height }]) => ({ rowLabel, centerY: (top + bottom) / 2, height }))
    .sort((a, b) => a.centerY - b.centerY);
}

/**
 * 좌석 안에 넣을 좌석 이름(예: J12)의 글자 크기. 높이의 38%를 넘지 않고, 글자 수가 많으면 폭에
 * 맞춰 줄인다(글자 폭 ≈ 0.62em, 좌우 7% 여유).
 */
export const seatFontSize = (seat: LayoutSeat): number =>
  Math.min(seat.h * 0.38, (seat.w * 0.86) / (Math.max(seat.seatLabel.length, 1) * 0.62));

/** 고를 수 있는 좌석인지. 비활성·선점 중·예매 완료는 누를 수 없다 */
export const isSelectable = (state: SeatRenderState): boolean => state === "available" || state === "selected";

const STATE_LABEL: Record<SeatRenderState, string> = {
  available: "선택 가능",
  selected: "선택함",
  held: "다른 사람이 선점 중",
  reserved: "예매 완료",
  inactive: "판매하지 않는 자리",
};

/** 읽어 주는 좌석 설명 — 「J7 선택 가능」 */
export const seatAriaLabel = (seat: LayoutSeat, state: SeatRenderState): string =>
  `${seat.seatLabel} ${STATE_LABEL[state]}`;

export interface SeatStateCounts {
  available: number;
  selected: number;
  held: number;
  reserved: number;
  inactive: number;
}

/** 범례에 함께 보여 줄 좌석 수 */
export function countSeatStates(
  seats: readonly LayoutSeat[],
  stateOf: (seat: LayoutSeat) => SeatRenderState,
): SeatStateCounts {
  const counts: SeatStateCounts = { available: 0, selected: 0, held: 0, reserved: 0, inactive: 0 };
  for (const seat of seats) counts[stateOf(seat)] += 1;
  return counts;
}
