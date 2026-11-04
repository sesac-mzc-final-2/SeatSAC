/** contracts/openapi/catalog.openapi.yaml — AuditoriumLayout (CAT-04) */

/**
 * 상영관 종류. `entities/screening`의 같은 이름 타입과 값이 같다 — 원래 상영관의 속성이고
 * 회차가 가져다 쓰는 것이라, 나중에 한 곳(여기)으로 합치고 screening은 `@x`로 받는 게 맞다.
 * 지금은 다른 담당 슬라이스를 건드리지 않으려고 두 곳에 두었다.
 */
export type ScreenType = "NORMAL" | "RECLINER";

/** 좌석 종류. UNKNOWN은 수집 당시 분류를 못 한 좌석(PhysicalBlockCode 40) */
export type SeatType = "NORMAL" | "RECLINER" | "UNKNOWN";

export interface LayoutSeat {
  seatId: number;
  /** 사람이 읽는 좌석 이름 (예: J7) */
  seatLabel: string;
  rowLabel: string;
  colNo: number;
  x: number;
  y: number;
  w: number;
  h: number;
  zoneNo?: number | null;
  seatType: SeatType;
  /** false면 판매하지 않는 자리 (고장·장애인석 구분 등). 배치도에는 희미하게 그린다 */
  active: boolean;
}

/** 스크린이 놓인 가로 구간 */
export interface LayoutScreen {
  xFrom: number;
  xTo: number;
}

export interface LayoutEntrance {
  x: number;
  y: number;
}

export interface LayoutVerticalAisle {
  xFrom: number;
  xTo: number;
}

export interface LayoutHorizontalAisle {
  yFrom: number;
  yTo: number;
}

/** 수집 출처 — 화면 아래에 표시한다 (준수 조건) */
export interface LayoutSource {
  provider: string;
  /** ISO-8601 +09:00 */
  fetchedAt: string;
}

export interface AuditoriumLayout {
  auditoriumId: number;
  name: string;
  screenType: ScreenType;
  /** 좌석 구성이 바뀌면 올라간다. 회차의 layoutVersion으로 조회해야 그 회차의 배치를 본다 */
  layoutVersion: number;
  width: number;
  height: number;
  screen: LayoutScreen;
  entrances: LayoutEntrance[];
  verticalAisles: LayoutVerticalAisle[];
  horizontalAisles: LayoutHorizontalAisle[];
  seats: LayoutSeat[];
  source: LayoutSource;
}

/**
 * 배치도에 그릴 좌석의 상태. 좌석 점유(AVAILABLE·HELD·RESERVED)는 booking이 주는 값이고(BKG-01),
 * 배치도는 그것을 모른 채 「어떻게 그릴지」만 받는다 — 그래서 점유 상태를 그대로 쓰지 않는다.
 */
export type SeatRenderState = "available" | "selected" | "held" | "reserved" | "inactive";
