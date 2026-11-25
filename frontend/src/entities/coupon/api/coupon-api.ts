import { ApiError, http } from "@/shared/api";
import type { CouponIssue, CurrentCouponEvent, MyCouponList } from "../model/types";

/**
 * CPN-01 지금 보여 줄 이벤트(진행 중, 없으면 가장 가까운 진행 예정). NORMAL 사용자도 200.
 * 404(보여 줄 이벤트 없음)는 오류가 아니라 「배너 없음」이라 null 로 돌려준다.
 */
export async function getCurrentCouponEvent(signal?: AbortSignal): Promise<CurrentCouponEvent | null> {
  try {
    return await http<CurrentCouponEvent>("/coupons/events/current", { signal });
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

/**
 * CPN-02 선착순 받기 (VIP 만, 1인 1매) → 201. `Idempotency-Key` 는 누를 때마다 새로, <b>재시도만 같은 값</b>.
 * 거절은 403 COUPON_NOT_ELIGIBLE · 409 COUPON_SOLD_OUT·COUPON_ALREADY_CLAIMED·COUPON_EVENT_NOT_OPEN·IDEMPOTENCY_*
 */
export const claimCoupon = (eventId: number, idempotencyKey: string) =>
  http<CouponIssue>(`/coupons/events/${eventId}/claim`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
  });

/** CPN-03 내 쿠폰 전부(받은 시각 내림차순, 페이징 없음) */
export const listMyCoupons = (signal?: AbortSignal) => http<MyCouponList>("/coupons/me", { signal });
