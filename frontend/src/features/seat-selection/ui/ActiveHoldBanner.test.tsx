import { act, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { holdPhase } from "../lib/hold-phase";
import { ActiveHoldBanner } from "./ActiveHoldBanner";

const renderBanner = (expiresAt: string | null) =>
  render(
    <MemoryRouter>
      <ActiveHoldBanner bookingId={7} expiresAt={expiresAt} payHref="/bookings/7/pay" onRelease={() => {}} releasing={false} />
    </MemoryRouter>,
  );

// 실제 만료 14:05:10 (KST)
const EXPIRES = "2026-11-16T14:05:10+09:00";
const at = (kst: string) => new Date(`2026-11-16T${kst}+09:00`);

describe("ActiveHoldBanner", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("만료 시각을 분 단위로 내려 「HH:mm 전까지」로 고정해 보여 준다 (매초 바뀌지 않는다)", () => {
    vi.setSystemTime(at("14:02:00"));
    renderBanner(EXPIRES);
    expect(screen.getByText(/14:05 전까지 결제를 완료해 주세요/)).toBeInTheDocument();
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
    expect(screen.queryByRole("timer")).not.toBeInTheDocument();
  });

  it("60초 이하가 되는 순간 「곧 끝나요」를 한 번 알리고, 만료되면 바꾼다", () => {
    vi.setSystemTime(at("14:03:00"));
    renderBanner(EXPIRES);
    expect(screen.getByRole("status")).toBeEmptyDOMElement();

    act(() => vi.advanceTimersByTime(69_000));   // 14:04:09 — 남은 61초, 아직 그대로
    expect(screen.getByRole("status")).toBeEmptyDOMElement();

    act(() => vi.advanceTimersByTime(1_200));    // 14:04:10.2 — 남은 59.8초
    expect(screen.getByRole("status")).toHaveTextContent("곧 선점 시간이 끝나요");

    act(() => vi.advanceTimersByTime(60_000));   // 14:05:10.2 — 지남
    expect(screen.getByRole("status")).toHaveTextContent("선점 시간이 지났어요");
    // 고정 문장은 그대로
    expect(screen.getByText(/14:05 전까지/)).toBeInTheDocument();
  });

  it("배너가 뜰 때 이미 60초 이하면 처음부터 「곧 끝나요」", () => {
    vi.setSystemTime(at("14:04:30"));
    renderBanner(EXPIRES);
    expect(screen.getByRole("status")).toHaveTextContent("곧 선점 시간이 끝나요");
  });

  it("만료 시각을 모르면 시각·「곧 끝나요」를 지어내지 않는다", () => {
    vi.setSystemTime(at("14:04:30"));
    renderBanner(null);
    expect(screen.getByText(/이어서 결제하거나 선점을 해제해 주세요/)).toBeInTheDocument();
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });
});

describe("holdPhase", () => {
  it("경계: 60초 정각은 ending, 0은 over", () => {
    const expires = Date.parse(EXPIRES);
    expect(holdPhase(EXPIRES, expires - 60_001)).toBe("open");
    expect(holdPhase(EXPIRES, expires - 60_000)).toBe("ending");
    expect(holdPhase(EXPIRES, expires)).toBe("over");
    expect(holdPhase(null, expires)).toBe("unknown");
  });
});
