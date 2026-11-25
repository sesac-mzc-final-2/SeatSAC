import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useRerenderAt } from "./use-rerender-at";

describe("useRerenderAt", () => {
  beforeEach(() => vi.useFakeTimers({ now: 1_000_000 }));
  afterEach(() => vi.useRealTimers());

  const renderCounter = (at: number | null) => {
    let renders = 0;
    renderHook(() => {
      renders += 1;
      useRerenderAt(at);
    });
    return () => renders;
  };

  it("그 시각이 지나면 한 번 다시 그리고 멈춘다", () => {
    const renders = renderCounter(1_000_000 + 5_000);
    expect(renders()).toBe(1);
    act(() => vi.advanceTimersByTime(5_100));
    expect(renders()).toBe(2);
    act(() => vi.advanceTimersByTime(60_000));
    expect(renders()).toBe(2);
  });

  it("상한(약 24.8일)보다 먼 시각도 상한에서 다시 재어 놓치지 않는다", () => {
    const day = 86_400_000;
    const renders = renderCounter(1_000_000 + 30 * day);
    act(() => vi.advanceTimersByTime(25 * day));
    expect(renders()).toBe(2); // 상한에서 한 번 깨어나 다시 건다
    act(() => vi.advanceTimersByTime(5 * day + 100));
    expect(renders()).toBe(3); // 그 시각 뒤 렌더 — 이제 멈춘다
    act(() => vi.advanceTimersByTime(30 * day));
    expect(renders()).toBe(3);
  });

  it("처음부터 지난 시각·null 이면 다시 그리지 않는다", () => {
    const past = renderCounter(1_000_000 - 1);
    const none = renderCounter(null);
    act(() => vi.advanceTimersByTime(10_000));
    expect(past()).toBe(1);
    expect(none()).toBe(1);
  });
});
