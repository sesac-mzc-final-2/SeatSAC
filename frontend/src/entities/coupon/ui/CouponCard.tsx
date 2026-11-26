import { dateOfIso, formatDateDots, serverNow, timeOfIso, useRerenderAt } from "@/shared/lib";
import { Badge } from "@/shared/ui";
import { couponStateLabel, isUsableCoupon } from "../lib/coupon";
import type { CouponIssue } from "../model/types";
import styles from "./CouponCard.module.css";

/** 내 쿠폰 한 장 (CPN-03): 할인율 · 이벤트 제목 · 상태 · 사용 기한 */
export function CouponCard({ coupon }: { coupon: CouponIssue }) {
  // 화면을 열어 둔 채 기한이 지나면 그 순간 「기간 만료」로 바꿔 그린다
  const now = serverNow();
  useRerenderAt(coupon.state === "ISSUED" ? coupon.expiresAt : null, now);
  const usable = isUsableCoupon(coupon, now);
  return (
    <article className={styles.card} data-usable={usable || undefined}>
      <div className={styles.rate}>
        <strong>{coupon.discountRate}%</strong>
        <span>할인</span>
      </div>
      <div className={styles.body}>
        <h3 className={styles.title}>{coupon.title}</h3>
        <p className={styles.expires}>
          {formatDateDots(dateOfIso(coupon.expiresAt))} {timeOfIso(coupon.expiresAt)}까지
        </p>
      </div>
      <Badge tone={usable ? "accent" : "muted"}>{usable ? "사용 가능" : couponStateLabel(expiredView(coupon))}</Badge>
    </article>
  );
}

/** 저장 상태가 ISSUED 여도 기한이 지났으면 「기간 만료」 (서버도 그렇게 돌려주지만 화면에 오래 떠 있을 때를 위해) */
const expiredView = (coupon: CouponIssue) => (coupon.state === "ISSUED" ? "EXPIRED" : coupon.state);
