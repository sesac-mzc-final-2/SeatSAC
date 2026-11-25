import { describe, expect, it } from "vitest";
import type { CouponIssue, CurrentCouponEvent } from "../model/types";
import {
  couponCountdownTarget,
  couponEventPhase,
  couponStateLabel,
  discountOf,
  finalAmountOf,
  formatLongCountdown,
  isUsableCoupon,
} from "./coupon";

const event: CurrentCouponEvent = {
  eventId: 3,
  title: "VIP 선착순 50% 할인",
  discountRate: 50,
  startsAt: "2026-11-27T14:00:00+09:00",
  endsAt: "2026-11-27T15:00:00+09:00",
  totalQuantity: 100,
  remainingQuantity: 37,
  eligibleGrade: "VIP",
  state: "UPCOMING",
  myIssue: null,
};
const at = (iso: string) => new Date(iso).getTime();

describe("쿠폰 금액", () => {
  it("할인은 원 단위 내림 (booking 과 같은 식)", () => {
    expect(discountOf(45000, 33)).toBe(14850);
    expect(discountOf(15000, 7)).toBe(1050);
    expect(discountOf(15001, 50)).toBe(7500);
    expect(finalAmountOf(15001, 50)).toBe(7501);
    expect(finalAmountOf(15000, 99)).toBe(150);
  });
});

describe("isUsableCoupon", () => {
  const coupon: CouponIssue = {
    couponIssueId: "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
    eventId: 3,
    title: "VIP 선착순 50% 할인",
    discountRate: 50,
    state: "ISSUED",
    expiresAt: "2026-11-27T15:00:00+09:00",
  };
  it("ISSUED 이고 만료 전만", () => {
    expect(isUsableCoupon(coupon, at("2026-11-27T14:59:59+09:00"))).toBe(true);
    expect(isUsableCoupon(coupon, at("2026-11-27T15:00:00+09:00"))).toBe(false);
    expect(isUsableCoupon({ ...coupon, state: "RESERVED" }, at("2026-11-27T14:00:00+09:00"))).toBe(false);
  });
  it("모르는 상태는 「사용할 수 없음」", () => {
    expect(couponStateLabel("ISSUED")).toBe("사용 가능");
    expect(couponStateLabel("SOMETHING_NEW")).toBe("사용할 수 없음");
  });
});

describe("couponEventPhase", () => {
  it("시작 시각이 지나면 화면은 OPEN, 종료가 지나면 CLOSED", () => {
    expect(couponEventPhase(event, at("2026-11-27T13:59:59+09:00"))).toBe("UPCOMING");
    expect(couponEventPhase(event, at("2026-11-27T14:00:00+09:00"))).toBe("OPEN");
    expect(couponEventPhase({ ...event, state: "OPEN" }, at("2026-11-27T15:00:00+09:00"))).toBe("CLOSED");
  });
  it("카운트다운은 시작 전엔 시작까지, 진행 중엔 종료까지", () => {
    expect(couponCountdownTarget(event, "UPCOMING")).toBe(event.startsAt);
    expect(couponCountdownTarget(event, "OPEN")).toBe(event.endsAt);
    expect(couponCountdownTarget(event, "CLOSED")).toBeNull();
  });
  it("시간 표기", () => {
    expect(formatLongCountdown(125)).toBe("2:05");
    expect(formatLongCountdown(3725)).toBe("1:02:05");
    expect(formatLongCountdown(-3)).toBe("0:00");
  });
});
