import type { AuditoriumLayout, LayoutSeat, SeatRenderState } from "../model/types";

/** 배치도 바깥 여백 (좌석 좌표 단위). 왼쪽은 열 이름, 위는 스크린이 들어간다 */
export const MAP_PADDING = { top: 56, right: 16, bottom: 28, left: 34 } as const;

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

/**
 * 왼쪽에 붙일 줄 이름. 좌석 하나하나에 이름을 적지 않고 줄 이름 + 열 번호로 읽게 한다
 * (좌석이 100개를 넘는 상영관에서 좌석마다 전체 이름을 적으면 읽을 수 없다).
 */
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

/** 좌석 안에 넣을 열 번호의 글자 크기 — 좌석이 작으면 글자도 같이 작아진다 */
export const seatFontSize = (seat: LayoutSeat): number => Math.min(seat.w, seat.h) * 0.52;

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
