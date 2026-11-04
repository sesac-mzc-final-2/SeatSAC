import { afterEach, describe, expect, it, vi } from "vitest";
import { resetServerClock, serverNow, syncServerClock } from "./server-clock";

describe("serverNow", () => {
  afterEach(() => {
    resetServerClock();
    vi.useRealTimers();
  });

  it("응답 전에는 내 시계 그대로다", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-16T05:00:00.000Z"));
    expect(serverNow()).toBe(Date.parse("2026-11-16T05:00:00.000Z"));
  });

  it("Date 헤더로 내 시계와의 차이를 맞춘다 (초 단위 절삭 0.5초 보정 · 왕복의 가운데)", () => {
    vi.useFakeTimers();
    // 내 시계가 서버보다 2분 늦다
    vi.setSystemTime(new Date("2026-11-16T05:00:00.000Z"));
    const sent = Date.now() - 200;
    syncServerClock("Mon, 16 Nov 2026 05:02:00 GMT", sent, Date.now());
    // 서버 05:02:00.5 를 왕복 가운데(04:59:59.9)에 찍었다 → 지금 서버 시각은 05:02:00.6
    expect(serverNow()).toBe(Date.parse("2026-11-16T05:02:00.600Z"));
  });

  it("캐시된 응답·잘못된 헤더·너무 긴 왕복은 쓰지 않는다", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-16T05:00:00.000Z"));
    const now = Date.now();
    syncServerClock("Mon, 16 Nov 2026 06:00:00 GMT", now, now, "30");
    syncServerClock("not a date", now, now);
    syncServerClock("Mon, 16 Nov 2026 06:00:00 GMT", now - 10_000, now);
    syncServerClock(null, now, now);
    expect(serverNow()).toBe(now);
  });
});
