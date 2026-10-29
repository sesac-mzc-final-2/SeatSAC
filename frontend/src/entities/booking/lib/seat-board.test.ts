import { describe, expect, it } from "vitest";
import type { SeatEvent, SeatSnapshot } from "../model/types";
import { applyBufferedEvents, applySeatEvent, availableCount, boardFromSnapshot, statusOf } from "./seat-board";

const snapshot: SeatSnapshot = {
  screeningId: 5123,
  layoutVersion: 1,
  seq: 418,
  generatedAt: "2026-11-16T14:03:00+09:00",
  seats: [
    { seatId: 1, status: "AVAILABLE" },
    { seatId: 2, status: "HELD" },
    { seatId: 3, status: "RESERVED" },
  ],
};

const event = (over: Partial<SeatEvent> & { seq: number }): SeatEvent => ({
  type: "HELD",
  screeningId: 5123,
  seatIds: [1],
  occurredAt: "2026-11-16T14:03:05+09:00",
  traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
  ...over,
});

describe("boardFromSnapshot", () => {
  it("좌석 상태와 seq를 그대로 담는다", () => {
    const board = boardFromSnapshot(snapshot);
    expect(board.seq).toBe(418);
    expect(board.layoutVersion).toBe(1);
    expect(statusOf(board, 2)).toBe("HELD");
  });

  it("스냅샷에 없는 좌석은 AVAILABLE로 본다", () => {
    expect(statusOf(boardFromSnapshot(snapshot), 999)).toBe("AVAILABLE");
  });
});

describe("applySeatEvent", () => {
  it("seq가 바로 다음이면 적용한다", () => {
    const result = applySeatEvent(boardFromSnapshot(snapshot), event({ seq: 419, seatIds: [1] }));
    expect(result.outcome).toBe("applied");
    expect(result.board.seq).toBe(419);
    expect(statusOf(result.board, 1)).toBe("HELD");
  });

  it("종류마다 좌석 상태가 정해져 있다", () => {
    const base = boardFromSnapshot(snapshot);
    expect(statusOf(applySeatEvent(base, event({ seq: 419, type: "RELEASED", seatIds: [2] })).board, 2)).toBe(
      "AVAILABLE",
    );
    expect(statusOf(applySeatEvent(base, event({ seq: 419, type: "EXPIRED", seatIds: [2] })).board, 2)).toBe(
      "AVAILABLE",
    );
    expect(statusOf(applySeatEvent(base, event({ seq: 419, type: "CONFIRMED", seatIds: [2] })).board, 2)).toBe(
      "RESERVED",
    );
  });

  it("한 이벤트의 좌석 여러 개를 한 번에 바꾼다", () => {
    const result = applySeatEvent(boardFromSnapshot(snapshot), event({ seq: 419, seatIds: [1, 3] }));
    expect(statusOf(result.board, 1)).toBe("HELD");
    expect(statusOf(result.board, 3)).toBe("HELD");
  });

  it("스냅샷에 이미 반영된 번호는 버린다", () => {
    const base = boardFromSnapshot(snapshot);
    const result = applySeatEvent(base, event({ seq: 418 }));
    expect(result.outcome).toBe("stale");
    expect(result.board).toBe(base);
  });

  it("번호가 띄어 있으면 적용하지 않고 gap을 알린다 (스냅샷 재조회용)", () => {
    const base = boardFromSnapshot(snapshot);
    const result = applySeatEvent(base, event({ seq: 421 }));
    expect(result.outcome).toBe("gap");
    expect(result.board).toBe(base);
    expect(statusOf(result.board, 1)).toBe("AVAILABLE");
  });

  it("다른 회차의 이벤트는 무시한다 (회차를 바꿔도 이전 구독이 남을 수 있다)", () => {
    const base = boardFromSnapshot(snapshot);
    expect(applySeatEvent(base, event({ seq: 419, screeningId: 9999 })).outcome).toBe("stale");
  });

  it("원래 상태를 바꾸지 않는다", () => {
    const base = boardFromSnapshot(snapshot);
    applySeatEvent(base, event({ seq: 419, seatIds: [1] }));
    expect(statusOf(base, 1)).toBe("AVAILABLE");
  });
});

describe("applyBufferedEvents", () => {
  it("SSE를 먼저 연 사이 모인 이벤트를 번호순으로 적용한다", () => {
    const result = applyBufferedEvents(boardFromSnapshot(snapshot), [
      event({ seq: 420, seatIds: [3], type: "RELEASED" }),
      event({ seq: 419, seatIds: [1], type: "HELD" }),
    ]);
    expect(result.outcome).toBe("applied");
    expect(result.board.seq).toBe(420);
    expect(statusOf(result.board, 1)).toBe("HELD");
    expect(statusOf(result.board, 3)).toBe("AVAILABLE");
  });

  it("스냅샷보다 오래된 이벤트만 모여 있으면 그대로 둔다", () => {
    const result = applyBufferedEvents(boardFromSnapshot(snapshot), [event({ seq: 417 }), event({ seq: 418 })]);
    expect(result.outcome).toBe("stale");
    expect(result.board.seq).toBe(418);
  });

  it("중간이 비면 그 앞까지만 적용하고 gap을 알린다", () => {
    const result = applyBufferedEvents(boardFromSnapshot(snapshot), [
      event({ seq: 419, seatIds: [1] }),
      event({ seq: 421, seatIds: [3] }),
    ]);
    expect(result.outcome).toBe("gap");
    expect(result.board.seq).toBe(419);
  });
});

describe("availableCount", () => {
  it("판매 가능 좌석 중 AVAILABLE만 센다", () => {
    expect(availableCount(boardFromSnapshot(snapshot), [1, 2, 3])).toBe(1);
  });
});
