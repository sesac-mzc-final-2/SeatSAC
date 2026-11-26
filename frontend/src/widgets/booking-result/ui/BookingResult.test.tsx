import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import type { Booking } from "@/entities/booking";
import type { PayOutcome } from "@/features/booking-payment";
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

const renderOutcome = (status: Booking["status"], outcome: PayOutcome) =>
  render(
    <MemoryRouter>
      <BookingResult
        booking={booking(status)}
        outcome={outcome}
        seatSelectHref="/screenings/3521"
        myPageHref="/me"
        homeHref="/"
        screening={null}
        movie={null}
        onRecheck={vi.fn()}
      />
    </MemoryRouter>,
  );

describe("BookingResult — 체크아웃 결과 (6.2.5)", () => {
  it("만료 + WILL_REFUND_IF_PAID: 「결제가 확인되면 환불」 안내와 예매 내역·다시 예매 버튼, 「환불 완료」는 없다", () => {
    renderOutcome("EXPIRED", { kind: "expired", refunded: false, refundNotice: "WILL_REFUND_IF_PAID" });
    expect(screen.getByText(/결제가 확인되면 환불을 진행하겠습니다/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /예매 내역 보기/ })).toHaveAttribute("href", "/me");
    expect(screen.getByRole("link", { name: "다시 예매하기" })).toHaveAttribute("href", "/screenings/3521");
    expect(screen.queryByText(/환불 완료/)).not.toBeInTheDocument();
  });

  it("만료 + REFUND_IN_PROGRESS: 「환불을 진행 중」", () => {
    renderOutcome("EXPIRED", { kind: "expired", refunded: false, refundNotice: "REFUND_IN_PROGRESS" });
    expect(screen.getByText(/환불을 진행 중입니다/)).toBeInTheDocument();
  });

  it("만료 + 안내 없음(결제 없음): 기존 문구와 좌석 다시 고르기", () => {
    renderOutcome("EXPIRED", { kind: "expired", refunded: false, refundNotice: null });
    expect(screen.getByText(/5분이 지나 자동으로 취소됐어요/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /좌석 다시 고르기/ })).toBeInTheDocument();
  });

  it("결제 실패는 방금 받은 결과의 사유를 쓴다 — 체크아웃 예매는 예매 조회에 결제 정보가 없다", () => {
    renderOutcome("PAYMENT_FAILED", { kind: "declined", failureCode: "PG_DECLINED" });
    expect(screen.getByText(/토스페이에서 결제가 승인되지 않았어요/)).toBeInTheDocument();
  });

  it("단계 시간 초과처럼 사유 코드가 없으면 받은 문구를 그대로", () => {
    renderOutcome("PAYMENT_FAILED", { kind: "declined", failureCode: null, message: "결제 준비가 늦어져 취소했어요." });
    expect(screen.getByText("결제 준비가 늦어져 취소했어요.")).toBeInTheDocument();
  });
});
