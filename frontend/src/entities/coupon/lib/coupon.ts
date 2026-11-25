import { serverNow } from "@/shared/lib";
import type { CouponIssue, CouponIssueState, CurrentCouponEvent } from "../model/types";

/** 할인 금액 = floor(원가 × 할인율 / 100) — booking 이 결제 준비 전에 같은 식으로 확정한다(ADR-118) */
export const discountOf = (amount: number, discountRate: number): number => Math.floor((amount * discountRate) / 100);

/** 최종 결제 금액 = 원가 − 할인 (표시용 예상값. 실제 금액은 체크아웃 응답 `amount.final`) */
export const finalAmountOf = (amount: number, discountRate: number): number =>
  amount - discountOf(amount, discountRate);

/** 결제에 쓸 수 있는 쿠폰: ISSUED 이고 아직 만료 전 (계약 CPN-03 — 결제 화면은 이것만 고르게 한다) */
export function isUsableCoupon(coupon: CouponIssue, now: number = serverNow()): boolean {
  return coupon.state === "ISSUED" && new Date(coupon.expiresAt).getTime() > now;
}

const STATE_LABEL: Record<string, string> = {
  ISSUED: "사용 가능",
  RESERVED: "결제 진행 중",
  USED: "사용 완료",
  EXPIRED: "기간 만료",
};

/** 모르는 상태는 「사용할 수 없음」 (열린 집합) */
export const couponStateLabel = (state: CouponIssueState): string => STATE_LABEL[state] ?? "사용할 수 없음";

/**
 * 화면 단계 — 서버가 준 `state` 를 기본으로 하되, 시각이 지났으면 화면 시계로 한 단계 앞서 보여 준다
 * (그 순간 API 를 다시 불러 서버 판단으로 맞춘다. 발급 여부는 언제나 claim 이 정한다)
 */
export function couponEventPhase(event: CurrentCouponEvent, now: number = serverNow()): CurrentCouponEvent["state"] {
  if (now >= new Date(event.endsAt).getTime()) return "CLOSED";
  if (event.state === "UPCOMING" && now >= new Date(event.startsAt).getTime()) return "OPEN";
  return event.state;
}

/** 카운트다운 기준 시각: 시작 전이면 시작, 진행 중이면 종료 */
export const couponCountdownTarget = (event: CurrentCouponEvent, phase = couponEventPhase(event)): string | null =>
  phase === "UPCOMING" ? event.startsAt : phase === "OPEN" ? event.endsAt : null;

/** `3725` → `1:02:05`, `125` → `2:05` */
export function formatLongCountdown(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(s / 3600);
  const minutes = Math.floor((s % 3600) / 60);
  const seconds = String(s % 60).padStart(2, "0");
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${seconds}` : `${minutes}:${seconds}`;
}
