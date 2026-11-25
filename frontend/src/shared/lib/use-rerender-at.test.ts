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

  it("판단한 시각을 넘기면, 그 판단과 effect 사이에 지난 시각도 한 번 더 그린다", () => {
    const target = 1_000_000 + 10;
    let renders = 0;
    renderHook(() => {
      renders += 1;
      // 첫 렌더는 만료 전에 판단했고(1_000_000), effect 가 돌 때는 이미 지났다
      const now = renders === 1 ? 1_000_000 : Date.now();
      if (renders === 1) vi.setSystemTime(target + 20);
      useRerenderAt(target, now);
    });
    expect(renders).toBe(1);
    act(() => vi.advanceTimersByTime(100));
    expect(renders).toBe(2); // 만료 뒤 시각으로 다시 그림 — 이제 멈춘다
    act(() => vi.advanceTimersByTime(10_000));
    expect(renders).toBe(2);
  });
});
