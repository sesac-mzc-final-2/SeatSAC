import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { AdminLayout } from "@/widgets/admin-layout";
import { AdminOverviewPage } from "./AdminOverviewPage";
import { AdminSectionPage } from "./AdminSectionPage";

const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const services = {
  items: [
    { service: "gateway", kind: "ROLLOUT", desired: 2, ready: 2, state: 0, hpa: null, stopped: false },
    { service: "booking", kind: "ROLLOUT", desired: 0, ready: 0, state: null, hpa: null, stopped: true },
    { service: "catalog", kind: "DEPLOYMENT", desired: 2, ready: 1, state: 2, hpa: null, stopped: false },
  ],
  nodes: [
    { name: "n1", zone: "ap-northeast-2a", cpuPercent: 30, memoryPercent: 50, capacityType: "ON_DEMAND" },
  ],
  asOf: "2026-11-25T14:00:05+09:00",
};

const signedIn = (groups: string[]): Session => ({
  ...unavailableSession,
  status: "authenticated",
  user: { id: "00000000-0000-4000-8000-000000000001", name: "관리자", email: null, groups },
});

function renderAdmin(session: Session, path = "/admin") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={session}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/admin" element={<AdminLayout />}>
              <Route index element={<AdminOverviewPage />} />
              <Route
                path="/admin/services"
                element={<AdminSectionPage icon="shield-check" title="서비스 상태" description="준비 중" />}
              />
            </Route>
          </Routes>
        </MemoryRouter>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
}

describe("관리자 대시보드 골격 (6.3.5)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("비로그인이면 로그인 안내, 로그인 뒤 지금 경로로 돌아온다", async () => {
    const signIn = vi.fn();
    const fetchMock = vi.spyOn(globalThis, "fetch");
    renderAdmin({ ...unavailableSession, status: "anonymous", signIn }, "/admin/services");
    expect(screen.getByRole("heading", { name: "관리자 로그인이 필요해요" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "로그인" }));
    expect(signIn).toHaveBeenCalledWith("/admin/services");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("토큰에 ADMIN 그룹이 없으면 권한 안내만, /ops 를 부르지 않는다", () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    renderAdmin(signedIn(["VIP"]));
    expect(screen.getByRole("heading", { name: "관리자 권한이 필요해요" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "관리자 메뉴" })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("ADMIN 이면 메뉴 + 개요: GET /ops/services 를 /api 없이 부르고 요약을 보여 준다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, services));
    renderAdmin(signedIn(["ADMIN"]));

    expect(screen.getByRole("navigation", { name: "관리자 메뉴" })).toBeInTheDocument();
    expect(await screen.findByText("14:00 기준")).toBeInTheDocument();
    expect(screen.getByText("3개")).toBeInTheDocument();
    expect(screen.getAllByText("1개")).toHaveLength(2);
    expect(screen.getByText("1대")).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0]![0])).toBe("/ops/services");

    await userEvent.click(screen.getByRole("link", { name: "서비스 상태" }));
    expect(await screen.findByRole("heading", { name: "서비스 상태" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "서비스 상태" })).toHaveAttribute("aria-current", "page");
  });

  it("토큰에는 ADMIN 이 있어도 ops-api 가 403 이면 권한 안내", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(403, { code: "FORBIDDEN", message: "x", traceId: "0123456789abcdef0123456789abcdef", details: [] }),
    );
    renderAdmin(signedIn(["ADMIN"]));
    expect(await screen.findByRole("heading", { name: "관리자 권한이 필요해요" })).toBeInTheDocument();
  });
});
