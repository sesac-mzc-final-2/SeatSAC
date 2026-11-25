import { queryOptions } from "@tanstack/react-query";
import { getCurrentCouponEvent, listMyCoupons } from "./coupon-api";

/**
 * 사용자마다 다른 값(myIssue·내 쿠폰)이라 키 앞을 `["me"]`(entities/user 의 `userKeys.all`)로 둔다 —
 * 로그아웃·다른 계정 로그인 때 사용자 캐시와 함께 지워진다
 */
export const couponKeys = {
  all: ["me", "coupons"] as const,
  currentEvent: () => [...couponKeys.all, "current-event"] as const,
  mine: () => [...couponKeys.all, "mine"] as const,
};

export const currentCouponEventQuery = () =>
  queryOptions({
    queryKey: couponKeys.currentEvent(),
    queryFn: ({ signal }) => getCurrentCouponEvent(signal),
    // 배너는 실패하면 조용히 숨긴다 — coupon 이 없거나 멈춰 있는 동안 30초마다 공통 503 토스트가 뜨지 않게
    meta: { silentError: true },
    // 남은 수량이 계속 바뀐다 — 화면에 있는 동안 30초마다 새로 (표시용, 판정은 claim)
    refetchInterval: 30_000,
  });

export const myCouponsQuery = () =>
  queryOptions({
    queryKey: couponKeys.mine(),
    queryFn: ({ signal }) => listMyCoupons(signal),
    // 결제 화면 쿠폰 선택은 못 읽으면 숨기고 쿠폰 없이 결제한다. 마이페이지 쿠폰 탭은 ErrorState 로 알린다
    meta: { silentError: true },
  });
