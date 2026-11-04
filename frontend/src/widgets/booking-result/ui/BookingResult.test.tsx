import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import type { Booking } from "@/entities/booking";
import { BookingResult } from "./BookingResult";

const booking = (status: Booking["status"]): Booking => ({
  bookingId: 7,
  screeningId: 3521,
  status,
  seatIds: [10001],
  seatLabels: ["A1"],
  seatCount: 1,
  amount: 15000,
  expiresAt: "2026-10-31T09:05:00+09:00",
  createdAt: "2026-10-31T09:00:00+09:00",
  payment: null,
});

const renderResult = (status: Booking["status"], onRecheck = vi.fn()) =>
  render(
    <MemoryRouter>
      <BookingResult
        booking={booking(status)}
        outcome={{ kind: "unknown" }}
        seatSelectHref="/screenings/3521"
        myPageHref="/me"
        homeHref="/"
        screening={null}
        movie={null}
        onRecheck={onRecheck}
      />
    </MemoryRouter>,
  );

describe("BookingResult — 결과 미확인", () => {
  it("아직 PENDING이면 없는 화면(/me)으로 보내지 않고 같은 화면에서 다시 조회한다", async () => {
    const onRecheck = vi.fn();
    renderResult("PENDING", onRecheck);
    expect(screen.queryByRole("link", { name: /예매 내역/ })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "결과 다시 확인" }));
    expect(onRecheck).toHaveBeenCalledTimes(1);
  });

  it("다시 조회해 확정됐으면 완료 결과를 그린다", () => {
    renderResult("CONFIRMED");
    expect(screen.getByRole("heading", { name: "예매가 완료됐어요" })).toBeInTheDocument();
  });

  it("결제가 끝나지 않은 결과에는 「결제 금액」이 아니라 「예매 금액」이라 쓴다", () => {
    renderResult("EXPIRED");
    expect(screen.getByText("예매 금액")).toBeInTheDocument();
    expect(screen.queryByText("총 결제 금액")).not.toBeInTheDocument();
  });
});
