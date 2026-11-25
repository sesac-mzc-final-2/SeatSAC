import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { Header } from "./Header";

const signedIn = (groups: string[]): Session => ({
  ...unavailableSession,
  status: "authenticated",
  user: { id: "00000000-0000-4000-8000-000000000001", name: "한채연", email: null, groups },
});

const renderHeader = (session: Session) =>
  render(
    <SessionContext.Provider value={session}>
      <MemoryRouter>
        <Header />
      </MemoryRouter>
    </SessionContext.Provider>,
  );

describe("Header 관리자 메뉴", () => {
  it("토큰 cognito:groups 에 ADMIN 이 있을 때만 「관리자」 → /admin", () => {
    renderHeader(signedIn(["VIP", "ADMIN"]));
    expect(screen.getByRole("link", { name: "관리자" })).toHaveAttribute("href", "/admin");
  });

  it("ADMIN 이 아니거나 비로그인이면 메뉴가 없다", () => {
    const { unmount } = renderHeader(signedIn(["VIP"]));
    expect(screen.queryByRole("link", { name: "관리자" })).not.toBeInTheDocument();
    unmount();
    renderHeader({ ...unavailableSession, status: "anonymous" });
    expect(screen.queryByRole("link", { name: "관리자" })).not.toBeInTheDocument();
  });
});
