import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { MyPage } from "./MyPage";

const jsonResponse = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

const profile = {
  userId: "00000000-0000-4000-8000-000000000001",
  email: "chaeyeon@example.com",
  name: "한채연",
  role: "USER",
  createdAt: "2026-10-28T09:00:00+09:00",
};

function renderPage(session: Session, path = "/me") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={session}>
        <MemoryRouter initialEntries={[path]}>
          <MyPage />
        </MemoryRouter>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
}

describe("MyPage (W8)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("비로그인이면 로그인 안내, 버튼은 /me 로 돌아오게 signIn", async () => {
    const signIn = vi.fn();
    renderPage({ ...unavailableSession, status: "anonymous", signIn });
    expect(screen.getByRole("heading", { name: "로그인이 필요해요" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));
    expect(signIn).toHaveBeenCalledWith("/me");
  });

  it("로그인되면 GET /users/me 로 프로필을 받아 보여 주고, 탭은 ?tab= 과 맞춘다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/users/me") return jsonResponse(profile);
      if (url.startsWith("/api/users/me/preferences")) {
        return jsonResponse({ preferredTheaters: [], preferredTimeSlot: null, preferredGenres: [] });
      }
      if (url.startsWith("/api/theaters")) return jsonResponse({ items: [] });
      return jsonResponse({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    });
    renderPage(
      {
        ...unavailableSession,
        status: "authenticated",
        user: { id: profile.userId, name: "한채연", email: profile.email },
      },
      "/me?tab=preferences",
    );

    expect(await screen.findByRole("heading", { name: "한채연" })).toBeInTheDocument();
    expect(screen.getByText(profile.email)).toBeInTheDocument();
    expect(screen.getByText("일반 회원")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "선호 설정" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByRole("form", { name: "선호 설정" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "예매 내역" }));
    expect(await screen.findByText("아직 예매 내역이 없어요")).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).startsWith("/api/users/me/bookings?"))).toBe(true);
  });
});
