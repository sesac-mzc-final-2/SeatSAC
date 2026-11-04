import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MyBooking, MyBookingPage } from "@/entities/user";
import { BookingHistory } from "./BookingHistory";

const FUTURE = "2999-01-01T14:30:00+09:00";
const PAST = "2020-01-01T14:30:00+09:00";

const booking = (id: number, status: MyBooking["status"], startsAt: string | null): MyBooking => ({
  bookingId: id,
  status,
  seatLabels: ["J7", "J8"],
  seatCount: 2,
  amount: 30000,
  createdAt: "2026-11-01T10:00:00+09:00",
  confirmedAt: status === "CONFIRMED" ? "2026-11-01T10:01:00+09:00" : null,
  canceledAt: null,
  screening:
    startsAt === null
      ? null
      : {
          screeningId: 5123,
          movieTitle: `영화 ${id}`,
          theaterName: "건대입구",
          auditoriumName: "1관",
          startsAt,
          endsAt: startsAt.replace("14:30", "16:28"),
        },
});

const page = (content: MyBooking[], totalPages = 1): MyBookingPage => ({
  content,
  page: 0,
  size: 20,
  totalElements: content.length,
  totalPages,
});

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderHistory() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <BookingHistory />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("BookingHistory (W8 예매 내역)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("상태 배지와 취소 버튼 조건: 예매 완료 + 상영 전 카드에만, 회차 없는 카드는 한 줄 안내", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(
        page([
          booking(1, "CONFIRMED", FUTURE),
          booking(2, "CONFIRMED", PAST),
          booking(3, "PAYMENT_FAILED", PAST),
          booking(4, "CANCELED", PAST),
          booking(5, "CONFIRMED", null),
        ]),
      ),
    );
    renderHistory();

    const cancelable = await screen.findByRole("article", { name: "영화 1" });
    expect(within(cancelable).getByText("예매 완료")).toBeInTheDocument();
    expect(within(cancelable).getByRole("button", { name: "예매 취소" })).toBeInTheDocument();
    expect(within(cancelable).getByText("30,000원")).toBeInTheDocument();

    const past = screen.getByRole("article", { name: "영화 2" });
    expect(within(past).queryByRole("button", { name: "예매 취소" })).not.toBeInTheDocument();

    const failed = screen.getByRole("article", { name: "영화 3" });
    expect(within(failed).getByText("결제 실패")).toBeInTheDocument();
    expect(within(failed).getByRole("link", { name: /다시 상영시간 보기/ })).toHaveAttribute("href", "/showtimes");

    expect(within(screen.getByRole("article", { name: "영화 4" })).getByText("취소됨")).toBeInTheDocument();

    const missing = screen.getByRole("article", { name: "예매 5" });
    expect(within(missing).getByText("상영 정보를 불러오지 못했어요")).toBeInTheDocument();
    expect(within(missing).queryByRole("button", { name: "예매 취소" })).not.toBeInTheDocument();
  });

  it("「예매 취소」→ 확인 대화상자 → POST /bookings/{id}/cancel → 목록 다시 조회", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(jsonResponse(page([booking(1, "CONFIRMED", FUTURE)])))
      .mockResolvedValueOnce(jsonResponse({ bookingId: 1, status: "CANCELED", canceledAt: "2026-11-02T09:00:00+09:00" }))
      .mockResolvedValueOnce(jsonResponse(page([booking(1, "CANCELED", FUTURE)])));
    renderHistory();

    await userEvent.click(await screen.findByRole("button", { name: "예매 취소" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("예매를 취소할까요?");
    await userEvent.click(within(dialog).getByRole("button", { name: "예매 취소하기" }));

    expect(await screen.findByText("취소됨")).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(url).toBe("/api/bookings/1/cancel");
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("Idempotency-Key")).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.queryByRole("button", { name: "예매 취소" })).not.toBeInTheDocument();
  });

  it("503 BOOKING_UNAVAILABLE 은 화면별 문구와 문의 코드", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(
        { code: "BOOKING_UNAVAILABLE", message: "booking down", traceId: "0123456789abcdef0123456789abcdef", details: [] },
        503,
      ),
    );
    renderHistory();
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("예매 서비스가 잠시 응답하지 않아요");
    expect(within(alert).getByRole("button", { name: /문의 코드 01234567/ })).toBeInTheDocument();
  });

  it("빈 목록은 상영시간표 링크", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(page([])));
    renderHistory();
    expect(await screen.findByText("아직 예매 내역이 없어요")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /상영시간표 보기/ })).toHaveAttribute("href", "/showtimes");
  });
});
