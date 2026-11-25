import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { ToastProvider } from "@/shared/ui";
import { CouponEventBanner } from "./CouponEventBanner";

const respond = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const authed: Session = { ...unavailableSession, status: "authenticated", user: { id: "u1", name: "채연", email: "a@b.c" } };
const profile = (grade: string) => ({ userId: "u1", email: "a@b.c", name: "채연", createdAt: "2026-10-28T09:00:00+09:00", grade });
const event = (overrides: Record<string, unknown> = {}) => ({
  eventId: 3,
  title: "VIP 선착순 50% 할인",
  discountRate: 50,
  startsAt: new Date(Date.now() - 60_000).toISOString(),
  endsAt: new Date(Date.now() + 3600_000).toISOString(),
  totalQuantity: 100,
  remainingQuantity: 37,
  eligibleGrade: "VIP",
  state: "OPEN",
  myIssue: null,
  ...overrides,
});

function renderBanner(session: Session = authed) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={session}>
        <ToastProvider>
          <MemoryRouter>
            <CouponEventBanner />
          </MemoryRouter>
        </ToastProvider>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
}

describe("CouponEventBanner (6.3.6)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("비로그인이면 API 를 부르지 않고 그리지 않는다", () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    const { container } = renderBanner({ ...unavailableSession, status: "anonymous" });
    expect(container.querySelector("section")).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("NORMAL 은 이벤트는 보이고 받기 대신 「VIP 전용」", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
      String(input) === "/api/users/me" ? respond(200, profile("NORMAL")) : respond(200, event()),
    );
    renderBanner();
    expect(await screen.findByRole("heading", { name: "VIP 선착순 50% 할인" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "VIP 전용" })).toBeDisabled();
    expect(screen.getByRole("timer")).toHaveTextContent("종료까지");
  });

  it("VIP 가 받기를 누르면 Idempotency-Key 를 붙여 claim, 끝나면 다시 읽는다", async () => {
    let claimed = false;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/users/me") return respond(200, profile("VIP"));
      if (url.endsWith("/claim")) {
        claimed = true;
        return respond(201, { couponIssueId: "c1", eventId: 3, title: "t", discountRate: 50, state: "ISSUED", expiresAt: "x" });
      }
      return respond(200, event(claimed ? { myIssue: { couponIssueId: "c1", state: "ISSUED" } } : {}));
    });
    renderBanner();
    await userEvent.click(await screen.findByRole("button", { name: /쿠폰 받기/ }));

    expect(await screen.findByText("받은 쿠폰이에요")).toBeInTheDocument();
    const claimCall = fetchMock.mock.calls.find(([url]) => String(url).endsWith("/coupons/events/3/claim"));
    const headers = (claimCall?.[1]?.headers ?? {}) as Record<string, string>;
    expect(headers["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByText("쿠폰을 받았어요! 결제할 때 쓸 수 있어요.")).toBeInTheDocument();
  });

  it("보여 줄 이벤트가 없으면(404) 그리지 않고, 등급(/users/me)도 읽지 않는다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
      String(input) === "/api/users/me"
        ? respond(200, profile("VIP"))
        : respond(404, { code: "NOT_FOUND", message: "", traceId: "0".repeat(32), details: [] }),
    );
    const { container } = renderBanner();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(String(fetchMock.mock.calls[0][0])).toBe("/api/coupons/events/current");
    expect(container.querySelector("section")).toBeNull();
  });

  it("등급을 읽는 중엔 중립 비활성, 등급을 못 읽으면(user 503) 열어 두고 claim 에 맡긴다", async () => {
    let profileStatus = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) !== "/api/users/me") return respond(200, event());
      if (profileStatus === 0) return new Promise<Response>(() => {}); // 끝나지 않는 조회
      return respond(503, { code: "SERVICE_UNAVAILABLE", message: "", traceId: "0".repeat(32), details: [] });
    });
    const view = renderBanner();
    expect(await screen.findByRole("button", { name: /확인 중/ })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "VIP 전용" })).not.toBeInTheDocument();
    view.unmount();

    profileStatus = 503;
    renderBanner();
    expect(await screen.findByRole("button", { name: /쿠폰 받기/ })).toBeEnabled();
  });
});
