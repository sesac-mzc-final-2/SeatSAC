import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";

export const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

export const errorBody = (code: string) => ({
  code,
  message: "x",
  traceId: "0123456789abcdef0123456789abcdef",
  details: [],
});

export const adminSession: Session = {
  ...unavailableSession,
  status: "authenticated",
  user: { id: "00000000-0000-4000-8000-000000000001", name: "관리자", email: null, groups: ["ADMIN"] },
};

/** 관리자 패널 하나를 ADMIN 세션·새 QueryClient로 그린다 (레이아웃 없이) */
export function renderPanel(panel: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={adminSession}>
        <MemoryRouter>{panel}</MemoryRouter>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
}
