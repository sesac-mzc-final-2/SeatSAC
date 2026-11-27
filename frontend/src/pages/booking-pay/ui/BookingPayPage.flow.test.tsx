import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionContext, unavailableSession } from "@/entities/session";
import { routePatterns } from "@/shared/config";
import { ToastProvider } from "@/shared/ui";
import { BookingPayPage } from "./BookingPayPage";

/**
 * 6.5.1c 통합 테스트 — 결제 화면 흐름을 처음부터 끝까지(쿠폰 고르기 → 체크아웃 → 결제창 → 확인 → 확정).
 * 흐름 함수 단위 테스트(`checkout-flow.test.ts`)와 달리 화면을 그리고 사용자가 누르는 대로 간다(결제창 여는 코드까지 진짜).
 * 서버는 fetch를 가로챈 작은 상태 기계다(booking 체크아웃 saga 상태 전이 — data-model 7-3). 포트원 SDK만 가짜.
 */
const portone = vi.hoisted(() => ({ requestPayment: vi.fn() }));
// 포트원 결제창(SDK)만 가짜로 — 결제창을 띄우는 화면 코드(`openTossPay`)는 그대로 돈다. 응답에 `code`가 없으면 결제 완료
vi.mock("@portone/browser-sdk/v2", () => ({ requestPayment: portone.requestPayment }));

const PAYMENT_ID = "3f2b8c1e-7a4d-4e9b-9c61-2d5f0a8e7b13";
const COUPON_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
const TRACE = "a".repeat(32);

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const notFound = () => json({ code: "NOT_FOUND", message: "개발용", traceId: TRACE, details: [] }, 404);

const coupon = {
  couponIssueId: COUPON_ID,
  eventId: 3,
  title: "VIP 50%",
  discountRate: 50,
  state: "ISSUED",
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
};

type Amount = { original: number; discount: number; final: number };

/** booking 체크아웃 saga를 흉내 내는 가짜 서버. `couponVerdict`가 "reject"면 쿠폰 예약 단계에서 거절한다 */
function fakeServer({ couponVerdict }: { couponVerdict: "accept" | "reject" }) {
  const expiresAt = new Date(Date.now() + 300_000).toISOString();
  const booking = {
    bookingId: 1042,
    screeningId: 5001,
    status: "PENDING",
    seatIds: [100107, 100108],
    seatLabels: ["J7", "J8"],
    seatCount: 2,
    amount: 30000,
    expiresAt,
    createdAt: new Date().toISOString(),
    confirmedAt: null as string | null,
    canceledAt: null,
    payment: null,
    checkout: null as { checkoutId: string; state: string } | null,
  };
  const starts: { couponIssueId?: string; method: string; key: string | null }[] = [];
  const confirms: { paymentId: string; key: string | null }[] = [];
  let checkout: Record<string, unknown> | null = null;

  const view = (state: string, amount: Amount, over: Record<string, unknown> = {}) => ({
    checkoutId: `c-${starts.length}`,
    bookingId: 1042,
    state,
    step: state,
    method: "TOSSPAY",
    attempt: starts.length,
    retryable: state === "COUPON_REJECTED",
    amount,
    coupon: null,
    payment: null,
    failure: null,
    refundNotice: null,
    expiresAt,
    updatedAt: new Date().toISOString(),
    pollAfterMs: 10,
    ...over,
  });

  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const key = new Headers(init?.headers).get("Idempotency-Key");

    if (url === "/api/bookings/1042/checkout" && method === "POST") {
      const body = JSON.parse(String(init?.body)) as { method: string; couponIssueId?: string };
      starts.push({ ...body, key });
      const withCoupon = body.couponIssueId !== undefined;
      const amount: Amount = withCoupon
        ? { original: 30000, discount: 15000, final: 15000 }
        : { original: 30000, discount: 0, final: 30000 };
      if (withCoupon && couponVerdict === "reject") {
        // 쿠폰만 거절 — 예매·좌석은 그대로(COUPON_REJECTED, 보상 없음)
        checkout = view("COUPON_REJECTED", amount, { failure: { code: "COUPON_NOT_USABLE", reason: "EXPIRED" }, pollAfterMs: null });
      } else {
        checkout = view("READY_TO_PAY", amount, {
          payment: {
            paymentId: PAYMENT_ID,
            portone: {
              storeId: "store-test",
              channelKey: "channel-test",
              paymentId: PAYMENT_ID,
              orderName: "SeatSAC 영화 예매 2석",
              totalAmount: amount.final,
              currency: "KRW",
              payMethod: "EASY_PAY",
            },
          },
        });
      }
      booking.checkout = { checkoutId: String(checkout.checkoutId), state: String(checkout.state) };
      return json({ checkoutId: checkout.checkoutId, bookingId: 1042, state: "STARTED", pollAfterMs: 10 }, 202);
    }
    if (url === "/api/bookings/1042/checkout/confirm" && method === "POST") {
      const body = JSON.parse(String(init?.body)) as { paymentId: string };
      confirms.push({ ...body, key });
      // 포트원 단건 조회로 승인 → 예매 확정 + 쿠폰 사용 명령
      checkout = { ...checkout, state: "CONFIRMED", step: "CONFIRMED", pollAfterMs: null };
      booking.status = "CONFIRMED";
      booking.confirmedAt = new Date().toISOString();
      booking.checkout = { checkoutId: String(checkout.checkoutId), state: "CONFIRMED" };
      coupon.state = "USED";
      return json({ checkoutId: checkout.checkoutId, bookingId: 1042, state: "VERIFYING", pollAfterMs: 10 }, 202);
    }
    if (url === "/api/bookings/1042/checkout") return checkout ? json(checkout) : notFound();
    if (url === "/api/bookings/1042") return json(booking);
    if (url.startsWith("/api/coupons/me")) return json({ items: coupon.state === "ISSUED" ? [coupon] : [] });
    return notFound();
  });

  return { starts, confirms };
}

function renderPay() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider
        value={{ ...unavailableSession, status: "authenticated", user: { id: "u-1", name: "한채연", email: "u@example.com", groups: ["VIP"] } }}
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

describe("결제 화면 흐름 (6.5.1c 통합 테스트)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    portone.requestPayment.mockReset();
    coupon.state = "ISSUED";
  });

  it("쿠폰을 고르면 할인된 금액으로 결제창이 열리고, 확인 뒤 예매가 확정된다", async () => {
    portone.requestPayment.mockResolvedValue({ paymentId: PAYMENT_ID });
    const server = fakeServer({ couponVerdict: "accept" });
    renderPay();

    // 쿠폰 고르기 — 결제 버튼이 예상 금액(50% 할인)을 보인다
    await userEvent.click(await screen.findByRole("radio", { name: /VIP 50%/ }));
    const pay = screen.getByRole("button", { name: /토스페이로 .* 결제하기/ });
    expect(pay).toHaveAccessibleName(/15,000원/);
    await userEvent.click(pay);

    expect(await screen.findByRole("heading", { name: "예매가 완료됐어요" })).toBeInTheDocument();

    // 체크아웃 시작: 쿠폰 ID + 멱등 키
    expect(server.starts).toEqual([{ method: "TOSSPAY", couponIssueId: COUPON_ID, key: expect.any(String) }]);
    // 결제창은 서버가 정한 최종 금액·paymentId 그대로 (화면이 계산한 값이 아니다)
    expect(portone.requestPayment).toHaveBeenCalledTimes(1);
    expect(portone.requestPayment).toHaveBeenCalledWith(
      expect.objectContaining({
        storeId: "store-test",
        paymentId: PAYMENT_ID,
        totalAmount: 15000,
        redirectUrl: expect.stringContaining("/bookings/1042/pay"),
      }),
    );
    // 확인 요청은 같은 paymentId, 시작과 다른 멱등 키
    expect(server.confirms).toEqual([{ paymentId: PAYMENT_ID, key: expect.any(String) }]);
    expect(server.confirms[0].key).not.toBe(server.starts[0].key);
    // 결과 요약은 원가가 아니라 확정 금액(할인 뒤)
    expect(await screen.findByText("−15,000원")).toBeInTheDocument();
  });

  it("쿠폰이 거절되면 같은 화면에 남아 쿠폰 선택을 풀고, 쿠폰 없이 다시 결제해 확정된다", async () => {
    portone.requestPayment.mockResolvedValue({ paymentId: PAYMENT_ID });
    const server = fakeServer({ couponVerdict: "reject" });
    renderPay();

    await userEvent.click(await screen.findByRole("radio", { name: /VIP 50%/ }));
    await userEvent.click(screen.getByRole("button", { name: /토스페이로 .* 결제하기/ }));

    // 거절 안내 — 결제창은 열리지 않았고(돈이 움직이지 않음) 다시 결제할 수 있다
    expect(await screen.findByRole("alert")).toHaveTextContent("기간이 지난 쿠폰");
    expect(portone.requestPayment).not.toHaveBeenCalled();
    expect(screen.getByRole("radio", { name: "쿠폰 사용 안 함" })).toBeChecked();
    const pay = screen.getByRole("button", { name: /토스페이로 .* 결제하기/ });
    expect(pay).toBeEnabled();
    expect(pay).toHaveAccessibleName(/30,000원/);

    await userEvent.click(pay);
    expect(await screen.findByRole("heading", { name: "예매가 완료됐어요" })).toBeInTheDocument();

    // 두 번째 시작은 쿠폰 없이, 새 멱등 키로
    expect(server.starts).toHaveLength(2);
    expect(server.starts[1]).toEqual({ method: "TOSSPAY", key: expect.any(String) });
    expect(server.starts[1].key).not.toBe(server.starts[0].key);
    expect(portone.requestPayment).toHaveBeenCalledWith(expect.objectContaining({ totalAmount: 30000 }));
  });

  it("결과 화면에는 결제 폼이 다시 열리지 않는다 — 같은 예매에 두 번째 결제를 막는다", async () => {
    portone.requestPayment.mockResolvedValue({ paymentId: PAYMENT_ID });
    fakeServer({ couponVerdict: "accept" });
    renderPay();

    await userEvent.click(await screen.findByRole("button", { name: /토스페이로 .* 결제하기/ }));
    const result = await screen.findByRole("heading", { name: "예매가 완료됐어요" });
    expect(screen.queryByRole("button", { name: /토스페이로 .* 결제하기/ })).not.toBeInTheDocument();
    expect(within(result.closest("section") ?? document.body).queryByRole("radio")).not.toBeInTheDocument();
  });
});
