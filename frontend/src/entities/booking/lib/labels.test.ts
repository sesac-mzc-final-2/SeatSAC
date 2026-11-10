import { describe, expect, it } from "vitest";
import {
  amountFor,
  bookingStatusLabel,
  failureMessage,
  formatAmount,
  formatCountdown,
  secondsLeft,
} from "./labels";

describe("금액", () => {
  it("1석 15,000원", () => {
    expect(amountFor(1)).toBe(15_000);
    expect(amountFor(4)).toBe(60_000);
  });

  it("세 자리마다 쉼표를 넣는다", () => {
    expect(formatAmount(30_000)).toBe("30,000원");
  });
});

describe("상태 문구", () => {
  it("예매 상태 5종", () => {
    expect(bookingStatusLabel("PENDING")).toBe("결제 대기");
    expect(bookingStatusLabel("CONFIRMED")).toBe("예매 완료");
    expect(bookingStatusLabel("PAYMENT_FAILED")).toBe("결제 실패");
    expect(bookingStatusLabel("EXPIRED")).toBe("시간 초과");
    expect(bookingStatusLabel("CANCELED")).toBe("취소됨");
  });

  it("결제 실패 사유를 사유별로 알려 준다", () => {
    expect(failureMessage("CARD_DECLINED")).toContain("거절");
    expect(failureMessage("INSUFFICIENT_BALANCE")).toContain("잔액");
  });

  it("모르는 사유·없는 사유는 공통 문구 (계약에 값이 추가돼도 깨지지 않게)", () => {
    expect(failureMessage(null)).toContain("좌석을 다시 선택");
    expect(failureMessage(undefined)).toContain("좌석을 다시 선택");
  });
});

describe("선점 남은 시간", () => {
  const now = Date.parse("2026-11-16T14:05:00+09:00");

  it("만료까지 남은 초", () => {
    expect(secondsLeft("2026-11-16T14:08:05+09:00", now)).toBe(185);
  });

  it("이미 지났으면 0 (음수로 내려가지 않는다)", () => {
    expect(secondsLeft("2026-11-16T14:04:00+09:00", now)).toBe(0);
  });

  it("값이 이상하면 0", () => {
    expect(secondsLeft("없는 시각", now)).toBe(0);
  });

  it("분:초로 표시한다", () => {
    expect(formatCountdown(185)).toBe("3:05");
    expect(formatCountdown(0)).toBe("0:00");
    expect(formatCountdown(600)).toBe("10:00");
  });
});
