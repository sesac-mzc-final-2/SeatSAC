import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError } from "@/shared/api";
import { useSeatHold } from "./use-seat-hold";

const holdSeats = vi.fn();
vi.mock("@/entities/booking", () => ({ holdSeats: (...args: unknown[]) => holdSeats(...args) }));

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
    {children}
  </QueryClientProvider>
);

const keyOf = (call: number): string => holdSeats.mock.calls[call][1] as string;
const conflict = new ApiError(409, { code: "SEAT_UNAVAILABLE", message: "", traceId: "0".repeat(32), details: [] });

async function holdTwice(firstError: unknown, second = { screeningId: 1, seatIds: [10, 11] }) {
  holdSeats.mockRejectedValueOnce(firstError).mockResolvedValueOnce({ bookingId: 5 });
  const onFailed = vi.fn();
  const onHeld = vi.fn();
  const { result } = renderHook(() => useSeatHold({ onHeld, onFailed }), { wrapper });
  act(() => result.current.hold({ screeningId: 1, seatIds: [10, 11] }));
  await waitFor(() => expect(onFailed).toHaveBeenCalled());
  act(() => result.current.hold(second));
  await waitFor(() => expect(onHeld).toHaveBeenCalled());
}

describe("useSeatHold 멱등 키", () => {
  beforeEach(() => holdSeats.mockReset());

  it("응답을 못 받고 같은 좌석으로 다시 보내면 같은 키를 쓴다", async () => {
    await holdTwice(new NetworkError(new TypeError("Failed to fetch")));
    expect(keyOf(1)).toBe(keyOf(0));
  });

  it("좌석 충돌(저장된 4xx) 뒤에는 새 키를 쓴다", async () => {
    await holdTwice(conflict);
    expect(keyOf(1)).not.toBe(keyOf(0));
  });

  it("결과를 몰라도 다른 좌석이면 새 키를 쓴다 (같은 키·다른 본문은 409)", async () => {
    await holdTwice(new NetworkError(new TypeError("Failed to fetch")), { screeningId: 1, seatIds: [12] });
    expect(keyOf(1)).not.toBe(keyOf(0));
  });
});
