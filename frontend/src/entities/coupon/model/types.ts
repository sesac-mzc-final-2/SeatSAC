/** contracts/openapi/coupon.openapi.yaml — CurrentCouponEvent · CouponIssue · MyCouponList (CPN-01~03, ADR-118) */

/** 서버 시각으로 계산한 이벤트 단계. UPCOMING now < startsAt · OPEN startsAt ≤ now < endsAt · CLOSED endsAt ≤ now */
export type CouponEventState = "UPCOMING" | "OPEN" | "CLOSED";

/** 열린 집합(x-extensible-enum) — 모르는 값은 「사용할 수 없음」으로 보여 준다 */
export type CouponIssueState = "ISSUED" | "RESERVED" | "USED" | "EXPIRED" | (string & {});

export interface MyIssue {
  couponIssueId: string;
  state: CouponIssueState;
}

export interface CurrentCouponEvent {
  eventId: number;
  title: string;
  /** 할인율(%) 1~99 */
  discountRate: number;
  /** ISO-8601 +09:00 */
  startsAt: string;
  endsAt: string;
  totalQuantity: number;
  /** 표시용(Valkey 값) — 몰릴 때 몇 장 차이가 날 수 있다. 발급 판정은 claim 이 한다 */
  remainingQuantity: number;
  /** 받을 수 있는 등급 — 지금은 VIP 만 (열린 집합) */
  eligibleGrade: string;
  state: CouponEventState;
  /** 이 사용자가 받은 쿠폰. 안 받았으면 null */
  myIssue: MyIssue | null;
}

export interface CouponIssue {
  couponIssueId: string;
  eventId: number;
  title: string;
  discountRate: number;
  state: CouponIssueState;
  /** = 이벤트 종료 시각 */
  expiresAt: string;
}

export interface MyCouponList {
  items: CouponIssue[];
}
