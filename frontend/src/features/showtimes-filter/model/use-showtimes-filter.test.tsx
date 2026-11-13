import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import { useShowtimesFilter } from "./use-showtimes-filter";

const wrapperAt =
  (path: string) =>
  ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>;

describe("useShowtimesFilter — 기본 극장", () => {
  it("URL 의 theaterId 가 선택 가능하면 선호보다 URL 이 우선", () => {
    const { result } = renderHook(() => useShowtimesFilter([1, 2, 3], [3]), { wrapper: wrapperAt("/showtimes?theaterId=2") });
    expect(result.current.filter.theaterId).toBe(2);
  });

  it("URL 값이 없으면 선택 가능한 선호 극장 중 순위가 가장 높은 곳", () => {
    const { result } = renderHook(() => useShowtimesFilter([1, 2, 3], [9, 3, 2]), { wrapper: wrapperAt("/showtimes") });
    expect(result.current.filter.theaterId).toBe(3);
  });

  it("URL 값이 목록 밖이어도 선호 극장으로 대체", () => {
    const { result } = renderHook(() => useShowtimesFilter([1, 2, 3], [2]), { wrapper: wrapperAt("/showtimes?theaterId=99") });
    expect(result.current.filter.theaterId).toBe(2);
  });

  it("선호가 없거나 모두 목록 밖이면 첫 번째 극장", () => {
    const none = renderHook(() => useShowtimesFilter([1, 2, 3]), { wrapper: wrapperAt("/showtimes") });
    expect(none.result.current.filter.theaterId).toBe(1);
    const outside = renderHook(() => useShowtimesFilter([1, 2, 3], [9]), { wrapper: wrapperAt("/showtimes") });
    expect(outside.result.current.filter.theaterId).toBe(1);
  });

  it("극장 목록을 아직 못 받았으면 null", () => {
    const { result } = renderHook(() => useShowtimesFilter(null, [2]), { wrapper: wrapperAt("/showtimes") });
    expect(result.current.filter.theaterId).toBeNull();
  });
});
