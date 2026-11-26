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

  it("쿠폰을 쓴 결제창 취소(PAYMENT_RETRYABLE) 뒤에는 쿠폰을 돌려받는 데 잠시 걸린다고 알린다(#206 리뷰 보충)", async () => {
    const coupon = {
      couponIssueId: "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
      eventId: 3,
      title: "VIP 50%",
      discountRate: 50,
      state: "ISSUED",
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    };
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === "/api/bookings/1042/checkout" && init?.method === "POST") {
        return json({ checkoutId: "c-1", bookingId: 1042, state: "STARTED", pollAfterMs: 1000 }, 202);
      }
      if (url === "/api/bookings/1042/checkout") {
        return json({
          checkoutId: "c-1", bookingId: 1042, state: "PAYMENT_RETRYABLE", step: "payment_declined", method: "TOSSPAY",
          attempt: 1, retryable: true, amount: { original: 30000, discount: 15000, final: 15000 }, coupon: null,
          payment: null, failure: { code: "PAYMENT_DECLINED", reason: "USER_CANCELED" }, refundNotice: null,
          expiresAt: pending.expiresAt, updatedAt: new Date().toISOString(), pollAfterMs: null,
        });
      }
      if (url === "/api/bookings/1042") return json(pending);
      if (url.startsWith("/api/coupons/me")) return json({ items: [coupon] });
      return json({ code: "NOT_FOUND", message: "개발용", traceId: "a".repeat(32), details: [] }, 404);
    });
    renderPay();

    await userEvent.click(await screen.findByRole("radio", { name: /VIP 50%/ }));
    await userEvent.click(screen.getByRole("button", { name: /토스페이로 .* 결제하기/ }));

    const notice = await screen.findByRole("alert");
    expect(notice).toHaveTextContent("결제를 취소했어요");
    expect(notice).toHaveTextContent("쿠폰은 돌려받는 데 잠시 걸려요");
    expect(screen.getByRole("button", { name: /토스페이로 .* 결제하기/ })).toBeEnabled();
  });
});
