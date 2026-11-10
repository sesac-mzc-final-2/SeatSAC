import { describe, expect, it } from "vitest";
import type { MyBooking } from "../model/types";
import { bookingStatusLabel, bookingStatusTone, canCancelBooking, isRetryableBooking } from "./booking-status";

const screening: NonNullable<MyBooking["screening"]> = {
  screeningId: 5123,
  movieTitle: "별이 지는 항구",
  theaterName: "건대입구",
  auditoriumName: "1관",
  startsAt: "2026-11-16T14:30:00+09:00",
  endsAt: "2026-11-16T16:28:00+09:00",
};

describe("booking-status (W8 상태 배지·취소 조건)", () => {
  it("배지 4종의 문구와 톤", () => {
    expect(bookingStatusLabel("CONFIRMED")).toBe("예매 완료");
    expect(bookingStatusTone("CONFIRMED")).toBe("confirmed");
    expect(bookingStatusLabel("PAYMENT_FAILED")).toBe("결제 실패");
    expect(bookingStatusLabel("EXPIRED")).toBe("시간 초과");
    expect(bookingStatusTone("EXPIRED")).toBe("muted");
    expect(bookingStatusLabel("CANCELED")).toBe("취소됨");
    expect(bookingStatusTone("CANCELED")).toBe("canceled");
  });

  it("예매 취소는 CONFIRMED + 상영 시작 전에만", () => {
    const before = new Date("2026-11-16T14:29:59+09:00");
    const after = new Date("2026-11-16T14:30:00+09:00");
    expect(canCancelBooking({ status: "CONFIRMED", screening }, before)).toBe(true);
    expect(canCancelBooking({ status: "CONFIRMED", screening }, after)).toBe(false);
    expect(canCancelBooking({ status: "CANCELED", screening }, before)).toBe(false);
    expect(canCancelBooking({ status: "PAYMENT_FAILED", screening }, before)).toBe(false);
  });

  it("회차 정보가 없으면(Catalog 실패) 취소 버튼을 보여 주지 않는다", () => {
    expect(canCancelBooking({ status: "CONFIRMED", screening: null })).toBe(false);
  });

  it("결제 실패·시간 초과만 다시 고르기 링크", () => {
    expect(isRetryableBooking("PAYMENT_FAILED")).toBe(true);
    expect(isRetryableBooking("EXPIRED")).toBe(true);
    expect(isRetryableBooking("CONFIRMED")).toBe(false);
    expect(isRetryableBooking("CANCELED")).toBe(false);
  });
});
