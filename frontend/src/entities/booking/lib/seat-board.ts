import type { SeatEvent, SeatSnapshot, SeatStatus } from "../model/types";

/** 지금 화면이 알고 있는 좌석 상태 + 어디까지 받은 이벤트인지 */
export interface SeatBoard {
  screeningId: number;
  layoutVersion: number;
  seq: number;
  statuses: Readonly<Record<number, SeatStatus>>;
}

export function boardFromSnapshot(snapshot: SeatSnapshot): SeatBoard {
  const statuses: Record<number, SeatStatus> = {};
  for (const seat of snapshot.seats) statuses[seat.seatId] = seat.status;
  return {
    screeningId: snapshot.screeningId,
    layoutVersion: snapshot.layoutVersion,
    seq: snapshot.seq,
    statuses,
  };
}

/** 이벤트 종류 → 좌석 상태 */
const NEXT_STATUS: Record<SeatEvent["type"], SeatStatus> = {
  HELD: "HELD",
  RELEASED: "AVAILABLE",
  CONFIRMED: "RESERVED",
  EXPIRED: "AVAILABLE",
};

/**
 * 이벤트를 적용한 결과.
 * - `applied` — 상태를 바꿨다
 * - `stale` — 스냅샷에 이미 반영된 번호라 버린다
 * - `gap` — 번호가 띄어 있다(유실). 호출한 쪽이 200ms 뒤 BKG-01 스냅샷을 다시 받아야 한다
 */
export type ApplyOutcome = "applied" | "stale" | "gap";

export interface ApplyResult {
  board: SeatBoard;
  outcome: ApplyOutcome;
}

/**
 * `seq == lastSeq + 1`일 때만 적용한다 (계약 결정 13번).
 * 유실을 허용하는 설계라 빈틈이면 이벤트로 메우려 하지 않고 스냅샷으로 되돌린다 —
 * 중간 이벤트를 모르는 채 뒤 이벤트만 적용하면 좌석 상태가 조용히 틀어진다.
 */
export function applySeatEvent(board: SeatBoard, event: SeatEvent): ApplyResult {
  if (event.screeningId !== board.screeningId) return { board, outcome: "stale" };
  if (event.seq <= board.seq) return { board, outcome: "stale" };
  if (event.seq !== board.seq + 1) return { board, outcome: "gap" };

  const statuses = { ...board.statuses };
  const next = NEXT_STATUS[event.type];
  for (const seatId of event.seatIds) statuses[seatId] = next;
  return { board: { ...board, seq: event.seq, statuses }, outcome: "applied" };
}

/**
 * SSE를 먼저 연결하고 스냅샷을 받는 사이에 모인 이벤트를 번호순으로 적용한다.
 * 하나라도 빈틈이 나오면 그 자리에서 멈추고 `gap`을 돌려준다(스냅샷 재조회).
 */
export function applyBufferedEvents(board: SeatBoard, events: readonly SeatEvent[]): ApplyResult {
  let current = board;
  for (const event of [...events].sort((a, b) => a.seq - b.seq)) {
    const result = applySeatEvent(current, event);
    if (result.outcome === "gap") return { board: current, outcome: "gap" };
    current = result.board;
  }
  return { board: current, outcome: current === board ? "stale" : "applied" };
}

export const statusOf = (board: SeatBoard, seatId: number): SeatStatus =>
  board.statuses[seatId] ?? "AVAILABLE";

/** 남은 좌석 수 — 배치도의 판매 가능 좌석 중 AVAILABLE인 것 */
export function availableCount(board: SeatBoard, sellableSeatIds: readonly number[]): number {
  return sellableSeatIds.reduce((count, seatId) => (statusOf(board, seatId) === "AVAILABLE" ? count + 1 : count), 0);
}
