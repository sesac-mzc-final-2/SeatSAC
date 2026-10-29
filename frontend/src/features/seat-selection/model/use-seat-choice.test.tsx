import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { LayoutSeat } from "@/entities/auditorium";
import { MAX_SEATS } from "@/entities/booking";
import { useSeatChoice } from "./use-seat-choice";

const seat = (seatId: number): LayoutSeat => ({
  seatId,
  seatLabel: `A${seatId}`,
  rowLabel: "A",
  colNo: seatId,
  x: seatId * 24,
  y: 0,
  w: 20,
  h: 20,
  seatType: "NORMAL",
  active: true,
});

describe("useSeatChoice", () => {
  it("고른 순서대로 쌓인다", () => {
    const { result } = renderHook(() => useSeatChoice());
    act(() => {
      result.current.toggle(seat(3));
      result.current.toggle(seat(1));
    });
    expect(result.current.seatIds).toEqual([3, 1]);
  });

  it("이미 고른 좌석을 다시 누르면 빠진다", () => {
    const { result } = renderHook(() => useSeatChoice());
    act(() => {
      result.current.toggle(seat(1));
      result.current.toggle(seat(1));
    });
    expect(result.current.seats).toHaveLength(0);
  });

  it("같은 렌더에서 연달아 불러도 4석을 넘기면 바로 false를 돌려준다", () => {
    const { result } = renderHook(() => useSeatChoice());
    const accepted: boolean[] = [];
    act(() => {
      for (let seatId = 1; seatId <= MAX_SEATS + 1; seatId += 1) {
        accepted.push(result.current.toggle(seat(seatId)));
      }
    });
    // 다섯 번째만 거절 — 반환값이 함수형 업데이터 실행 시점에 밀리면 여기서 true가 섞인다
    expect(accepted).toEqual([true, true, true, true, false]);
    expect(result.current.seats).toHaveLength(MAX_SEATS);
    expect(result.current.full).toBe(true);
  });

  it("clear로 비운다", () => {
    const { result } = renderHook(() => useSeatChoice());
    act(() => {
      result.current.toggle(seat(1));
      result.current.clear();
    });
    expect(result.current.seats).toHaveLength(0);
    expect(result.current.has(1)).toBe(false);
  });
});
