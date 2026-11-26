import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Booking } from "@/entities/booking";
import { BookingSummaryCard } from "./BookingSummaryCard";

const booking = (status: Booking["status"]): Booking => ({
  bookingId: 7,
  screeningId: 3521,
  status,
  seatIds: [10001, 10002],
  seatLabels: ["A1", "A2"],
  seatCount: 2,
  amount: 30000,
  expiresAt: "2026-10-31T09:05:00+09:00",
  createdAt: "2026-10-31T09:00:00+09:00",
  payment: null,
});

const amountRow = () => screen.getByText(/결제 금액$|결제 예정 금액$/).parentElement as HTMLElement;

describe("BookingSummaryCard — 쿠폰 할인 금액 (#206 리뷰)", () => {
  it("쿠폰이 없으면 지금처럼 한 줄 — 원가가 결제 금액", () => {
    render(<BookingSummaryCard booking={booking("PENDING")} screening={null} movie={null} pricing={{ original: 30000, discount: 0, final: 30000 }} />);
    expect(amountRow()).toHaveTextContent("총 결제 금액");
    expect(amountRow()).toHaveTextContent("30,000원");
    expect(screen.queryByText("쿠폰 할인")).not.toBeInTheDocument();
  });

  it("결제 전 쿠폰을 고르면 상품 금액 · 쿠폰 할인 · 결제 예정 금액 — 「결제 금액」이 원가로 하나 더 보이지 않는다", () => {
    render(<BookingSummaryCard booking={booking("PENDING")} screening={null} movie={null} pricing={{ original: 30000, discount: 15000, final: 15000 }} />);
    expect(screen.getByText("상품 금액").nextSibling).toHaveTextContent("30,000원");
    expect(screen.getByText("쿠폰 할인").nextSibling).toHaveTextContent("−15,000원");
    expect(amountRow()).toHaveTextContent("결제 예정 금액");
    expect(amountRow()).toHaveTextContent("15,000원");
    expect(screen.queryByText("총 결제 금액")).not.toBeInTheDocument();
  });

  it("확정 뒤에는 체크아웃 확정 금액을 「총 결제 금액」으로", () => {
    render(<BookingSummaryCard booking={booking("CONFIRMED")} screening={null} movie={null} pricing={{ original: 30000, discount: 3000, final: 27000 }} />);
    expect(amountRow()).toHaveTextContent("총 결제 금액");
    expect(amountRow()).toHaveTextContent("27,000원");
  });
});
