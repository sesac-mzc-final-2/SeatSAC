import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { ToastProvider } from "@/shared/ui";
import { ShowtimesPage } from "./ShowtimesPage";

const jsonResponse = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

const theaters = [
  { theaterId: 1, name: "가산디지털", region: "서울", hasLayout: true },
  { theaterId: 2, name: "건대입구", region: "서울", hasLayout: true },
];

const screening = (screeningId: number, theaterId: number, hhmm: string) => ({
  screeningId,
  movie: { movieId: 7, title: "테스트 영화", viewGrade: "ALL" },
  theater: { theaterId, name: theaters.find((t) => t.theaterId === theaterId)!.name },
  auditorium: { auditoriumId: theaterId * 10, name: "1관", screenType: "NORMAL" },
  layoutVersion: 1,
  businessDate: "2026-11-14",
  startsAt: `2026-11-14T${hhmm}:00+09:00`,
  endsAt: `2026-11-14T23:50:00+09:00`,
  filmFormat: "2D",
  totalSeats: 100,
  remainingSeats: 50,
  remainingSeatsSource: "BOOKING",
});

const loggedIn: Session = {
  ...unavailableSession,
  status: "authenticated",
  user: { id: "00000000-0000-4000-8000-000000000001", name: "한채연", email: null },
};

function mockApi(preferences: unknown) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    if (url.startsWith("/api/users/me/preferences")) return jsonResponse(preferences);
    if (url.startsWith("/api/theaters")) return jsonResponse({ items: theaters });
    if (url.startsWith("/api/screenings")) {
      const theaterId = Number(new URL(url, "http://x").searchParams.get("theaterId"));
      return jsonResponse({ items: [screening(theaterId * 100 + 1, theaterId, "10:00"), screening(theaterId * 100 + 2, theaterId, "18:30")] });
    }
    return new Response(null, { status: 404 });
  });
}

function renderPage(session: Session, path = "/showtimes") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const tree = (s: Session) => (
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={s}>
        <ToastProvider>
          <MemoryRouter initialEntries={[path]}>
            <ShowtimesPage />
          </MemoryRouter>
        </ToastProvider>
      </SessionContext.Provider>
    </QueryClientProvider>
  );
  const result = render(tree(session));
  return { ...result, setSession: (s: Session) => result.rerender(tree(s)) };
}

describe("ShowtimesPage — 선호 반영 (USR-05)", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-14T09:00:00+09:00"));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("로그인하면 1순위 선호 극장이 기본 선택되고, 선호 극장 탭·선호 시간대 회차에 표시", async () => {
    mockApi({ preferredTheaters: [{ rank: 1, theaterId: 2 }], preferredTimeSlot: "EVENING", preferredGenres: [] });
    renderPage(loggedIn);

    const tabs = await screen.findByRole("group", { name: "극장 선택" });
    expect(await within(tabs).findByRole("button", { name: "건대입구, 선호 극장" })).toHaveAttribute("aria-pressed", "true");
    expect(within(tabs).getByRole("button", { name: "가산디지털" })).toHaveAttribute("aria-pressed", "false");

    expect(await screen.findByRole("button", { name: /^18:30 시작.*선호 시간대$/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^10:00 시작/ })).not.toHaveAccessibleName(/선호 시간대/);
    expect(screen.getByText(/선호 극장 · 선호 시간대\(저녁 17~21시\)/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "선호 바꾸기" })).toHaveAttribute("href", "/me?tab=preferences");
  });

  it("주소에 theaterId 가 있으면 선호보다 주소가 우선", async () => {
    mockApi({ preferredTheaters: [{ rank: 1, theaterId: 2 }], preferredTimeSlot: null, preferredGenres: [] });
    renderPage(loggedIn, "/showtimes?theaterId=1");
    const tabs = await screen.findByRole("group", { name: "극장 선택" });
    expect(await within(tabs).findByRole("button", { name: "가산디지털" })).toHaveAttribute("aria-pressed", "true");
    await screen.findByRole("button", { name: /^10:00 시작/ });
    expect(screen.queryByRole("button", { name: /선호 시간대/ })).not.toBeInTheDocument();
  });

  it("비로그인이면 선호를 조회하지 않고 첫 극장, 표시 없음", async () => {
    const fetchMock = mockApi({ preferredTheaters: [{ rank: 1, theaterId: 2 }], preferredTimeSlot: "EVENING", preferredGenres: [] });
    renderPage({ ...unavailableSession, status: "anonymous" });
    const tabs = await screen.findByRole("group", { name: "극장 선택" });
    expect(await within(tabs).findByRole("button", { name: "가산디지털" })).toHaveAttribute("aria-pressed", "true");
    await screen.findByRole("button", { name: /^18:30 시작/ });
    expect(screen.queryByRole("button", { name: /선호/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "선호 바꾸기" })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes("/users/me/preferences"))).toBe(false);
  });

  it("선호 조회가 실패해도 오류 없이 첫 극장으로", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url.startsWith("/api/users/me/preferences")) {
        return new Response(JSON.stringify({ code: "INTERNAL_ERROR", message: "x", traceId: "0".repeat(32), details: [] }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (url.startsWith("/api/theaters")) return jsonResponse({ items: theaters });
      return jsonResponse({ items: [screening(101, 1, "18:30")] });
    });
    renderPage(loggedIn);
    const tabs = await screen.findByRole("group", { name: "극장 선택" });
    expect(await within(tabs).findByRole("button", { name: "가산디지털" })).toHaveAttribute("aria-pressed", "true");
    expect(await screen.findByRole("button", { name: /^18:30 시작/ })).not.toHaveAccessibleName(/선호/);
  });

  it("선호 응답이 제한 시간(3초) 안에 오지 않으면 끊고 선호 없이 첫 극장으로", async () => {
    // beforeEach 는 Date 만 가짜로 둔다 → 이 테스트는 setTimeout 까지 가짜로 다시 건다
    vi.useRealTimers();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date("2026-11-14T09:00:00+09:00"));
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.startsWith("/api/users/me/preferences")) {
        // 응답하지 않는 요청 — 끊길 때만 끝난다
        return new Promise<Response>((_, reject) => {
          init?.signal?.addEventListener("abort", () => reject(init.signal?.reason));
        });
      }
      if (url.startsWith("/api/theaters")) return jsonResponse({ items: theaters });
      return jsonResponse({ items: [screening(101, 1, "18:30")] });
    });
    renderPage(loggedIn);
    await screen.findByRole("group", { name: "극장 선택" });
    expect(screen.queryByRole("button", { name: /^18:30 시작/ })).not.toBeInTheDocument();

    await vi.advanceTimersByTimeAsync(3000);
    const tabs = screen.getByRole("group", { name: "극장 선택" });
    await waitFor(() => expect(within(tabs).getByRole("button", { name: "가산디지털" })).toHaveAttribute("aria-pressed", "true"));
    expect(await screen.findByRole("button", { name: /^18:30 시작/ })).not.toHaveAccessibleName(/선호/);
  });

  it("로그아웃되면 캐시에 남은 선호도 바로 쓰지 않는다", async () => {
    mockApi({ preferredTheaters: [{ rank: 1, theaterId: 2 }], preferredTimeSlot: "EVENING", preferredGenres: [] });
    const { setSession } = renderPage(loggedIn);
    expect(await screen.findByRole("button", { name: "건대입구, 선호 극장" })).toBeInTheDocument();

    setSession({ ...unavailableSession, status: "anonymous" });
    expect(screen.queryByRole("button", { name: /선호 극장/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /선호 시간대/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "선호 바꾸기" })).not.toBeInTheDocument();
  });
});
