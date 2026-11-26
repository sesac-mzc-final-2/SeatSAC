import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionContext, unavailableSession } from "@/entities/session";
import { ToastProvider } from "@/shared/ui";
import { routePatterns } from "@/shared/config";
import { BookingPayPage } from "./BookingPayPage";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const pending = {
  bookingId: 1042,
  screeningId: 5001,
  status: "PENDING",
  seatIds: [100107, 100108],
  seatLabels: ["J7", "J8"],
  seatCount: 2,
  amount: 30000,
  expiresAt: new Date(Date.now() + 300_000).toISOString(),
  createdAt: new Date().toISOString(),
  confirmedAt: null,
  canceledAt: null,
  payment: null,
  checkout: null,
};

function renderPay() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider
        value={{ ...unavailableSession, status: "authenticated", user: { id: "u-1", name: "남궁일", email: "u@example.com", groups: [] } }}
      >
        <ToastProvider>
          <MemoryRouter initialEntries={["/bookings/1042/pay"]}>
            <Routes>
              <Route path={routePatterns.bookingPay} element={<BookingPayPage />} />
            </Routes>
          </MemoryRouter>
        </ToastProvider>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
}

describe("BookingPayPage (W5 체크아웃)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("재시도 한도(409 RETRY_LIMIT)면 안내를 띄우고 결제 버튼을 잠근다 — 다시 눌러도 같은 409라서(#206 리뷰 Minor 1)", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === "/api/bookings/1042/checkout" && init?.method === "POST") {
        return json(
          { code: "BOOKING_STATE_CONFLICT", message: "개발용", traceId: "a".repeat(32), details: [{ field: "attempt", value: 3, reason: "RETRY_LIMIT" }] },
          409,
        );
      }
      if (url === "/api/bookings/1042") return json(pending);
      if (url.startsWith("/api/coupons/me")) return json({ items: [] });
      return json({ code: "NOT_FOUND", message: "개발용", traceId: "a".repeat(32), details: [] }, 404);
    });
    renderPay();

    const pay = await screen.findByRole("button", { name: /토스페이로 .* 결제하기/ });
    expect(pay).toBeEnabled();
    await userEvent.click(pay);

    expect(await screen.findByRole("alert")).toHaveTextContent("3번까지");
    expect(screen.getByRole("button", { name: /토스페이로 .* 결제하기/ })).toBeDisabled();
  });
});
