import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { SeatEvent, SeatSnapshot } from "@/entities/booking";
import { useSeatBoard } from "./use-seat-board";

/** jsdom에는 EventSource가 없다 — 열린 연결을 테스트가 직접 조종할 수 있게 가짜로 둔다 */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  readonly listeners = new Map<string, Set<(event: Event) => void>>();
  closed = false;

  constructor(readonly url: string) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: (event: Event) => void) {
    const set = this.listeners.get(type) ?? new Set();
    set.add(listener);
    this.listeners.set(type, set);
  }

  removeEventListener(type: string, listener: (event: Event) => void) {
    this.listeners.get(type)?.delete(listener);
  }

  close() {
    this.closed = true;
  }

  emitOpen() {
    for (const listener of this.listeners.get("open") ?? []) listener(new Event("open"));
  }

  emitSeat(event: SeatEvent) {
    const message = new MessageEvent("seat", { data: JSON.stringify(event) });
    for (const listener of this.listeners.get("seat") ?? []) listener(message);
  }

  static latest() {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1];
  }
}

const snapshot = (seq: number): SeatSnapshot => ({
  screeningId: 5123,
  layoutVersion: 1,
  seq,
  generatedAt: "2026-11-16T14:03:00+09:00",
  seats: [
    { seatId: 1, status: "AVAILABLE" },
    { seatId: 2, status: "AVAILABLE" },
  ],
});

const event = (over: Partial<SeatEvent> & { seq: number }): SeatEvent => ({
  type: "HELD",
  screeningId: 5123,
  seatIds: [1],
  occurredAt: "2026-11-16T14:03:05+09:00",
  traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
  ...over,
});

const jsonResponse = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("useSeatBoard (BKG-01 + BKG-05)", () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal("EventSource", FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("스냅샷을 받아 좌석 상태를 세운다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(snapshot(418)));
    const { result } = renderHook(() => useSeatBoard(5123), { wrapper });

    await waitFor(() => expect(result.current.board).not.toBeNull());
    expect(result.current.board?.seq).toBe(418);
    expect(result.current.statusOfSeat(1)).toBe("AVAILABLE");
  });

  it("다음 번호 이벤트가 오면 바로 반영한다 (다른 사람의 선점)", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(snapshot(418)));
    const { result } = renderHook(() => useSeatBoard(5123), { wrapper });
    await waitFor(() => expect(result.current.board).not.toBeNull());

    FakeEventSource.latest().emitSeat(event({ seq: 419, seatIds: [1], type: "HELD" }));

    await waitFor(() => expect(result.current.statusOfSeat(1)).toBe("HELD"));
    expect(result.current.board?.seq).toBe(419);
  });

  it("연결되면 live가 켜진다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(snapshot(418)));
    const { result } = renderHook(() => useSeatBoard(5123), { wrapper });
    await waitFor(() => expect(result.current.board).not.toBeNull());

    FakeEventSource.latest().emitOpen();
    await waitFor(() => expect(result.current.live).toBe(true));
  });

  it("번호에 빈틈이 생기면 이벤트를 적용하지 않고 스냅샷을 다시 받는다", async () => {
    const fetchSpy = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(snapshot(418)))
      .mockResolvedValueOnce(jsonResponse({ ...snapshot(421), seats: [
        { seatId: 1, status: "RESERVED" },
        { seatId: 2, status: "AVAILABLE" },
      ] }));

    const { result } = renderHook(() => useSeatBoard(5123), { wrapper });
    await waitFor(() => expect(result.current.board).not.toBeNull());

    // 419·420을 놓치고 421이 먼저 도착한 상황
    FakeEventSource.latest().emitSeat(event({ seq: 421, seatIds: [1], type: "CONFIRMED" }));

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.board?.seq).toBe(421));
    // 빈틈 이벤트를 그대로 적용한 게 아니라 스냅샷에서 받은 값이다
    expect(result.current.statusOfSeat(1)).toBe("RESERVED");
  });

  it("스냅샷에 이미 들어 있는 번호의 이벤트는 무시한다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(snapshot(418)));
    const { result } = renderHook(() => useSeatBoard(5123), { wrapper });
    await waitFor(() => expect(result.current.board).not.toBeNull());

    FakeEventSource.latest().emitSeat(event({ seq: 418, seatIds: [1], type: "HELD" }));

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(result.current.statusOfSeat(1)).toBe("AVAILABLE");
  });

  it("화면을 떠나면 연결을 닫는다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(snapshot(418)));
    const { result, unmount } = renderHook(() => useSeatBoard(5123), { wrapper });
    await waitFor(() => expect(result.current.board).not.toBeNull());

    const source = FakeEventSource.latest();
    unmount();
    expect(source.closed).toBe(true);
  });
});
