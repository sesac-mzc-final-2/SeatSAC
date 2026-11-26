import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import { discountOf, finalAmountOf, isUsableCoupon, myCouponsQuery } from "@/entities/coupon";
import { formatWon, serverNow, useRerenderAt } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import styles from "./CouponSelect.module.css";

interface Props {
  /** 예매 원가 (좌석 수 × 15,000원) */
  amount: number;
  /** 고른 쿠폰의 `couponIssueId` — 쿠폰 없이 결제면 null. 체크아웃 요청 `couponIssueId` 에 그대로 넣는다 */
  value: string | null;
  onChange: (couponIssueId: string | null) => void;
  /** 결제 진행 중에는 바꾸지 못하게 */
  disabled?: boolean;
}

/**
 * 결제 화면 쿠폰 선택 (CPN-03, 6.3.6). 쓸 수 있는 쿠폰(ISSUED · 만료 전)만 고르게 하고, 예상 할인·결제 금액을 보여 준다.
 * 금액은 표시용 예상값이다 — 실제 금액은 booking 이 체크아웃에서 확정한다(`amount {original, discount, final}`, ADR-118).
 * 쓸 쿠폰이 없거나 내 쿠폰을 못 읽으면 아무것도 그리지 않는다(쿠폰 없이 결제는 그대로 된다).
 */
export function CouponSelect({ amount, value, onChange, disabled = false }: Props) {
  const coupons = useQuery(myCouponsQuery());
  // 쓸 수 있는지 판단한 시각과 다시 그릴 시각을 같은 「지금」으로 — 따로 읽으면 그 사이에 만료된 쿠폰을 놓친다
  const now = serverNow();
  const items = coupons.data?.items ?? [];
  // 결제 진행 중(disabled)에는 고른 쿠폰을 상태와 상관없이 그대로 둔다 — 체크아웃이 예약하면 RESERVED 가 되는데,
  // 그때 내 쿠폰을 다시 읽어도 선택이 풀리거나 목록이 사라지면 안 된다
  const usable = items.filter(
    (coupon) => isUsableCoupon(coupon, now) || (disabled && coupon.couponIssueId === value),
  );
  const selected = usable.find((coupon) => coupon.couponIssueId === value) ?? null;
  // 가장 먼저 끝나는 쿠폰의 기한에 다시 그려, 지난 쿠폰을 목록·선택에서 뺀다
  useRerenderAt(
    usable.length > 0 ? Math.min(...usable.map((coupon) => new Date(coupon.expiresAt).getTime())) : null,
    now,
  );

  // 고른 쿠폰을 더는 쓸 수 없으면(만료·다른 결제에 예약) 선택을 푼다. 내 쿠폰을 끝내 못 읽어 목록이 숨겨진 때도 —
  // 화면에 없는 쿠폰이 결제에 실려 가지 않게(읽는 중에는 그대로 둔다)
  const settled = coupons.isSuccess || (coupons.isError && !coupons.isFetching);
  useEffect(() => {
    if (!disabled && value !== null && settled && selected === null) onChange(null);
  }, [disabled, value, settled, selected, onChange]);

  /** 누르는 순간 다시 확인 — 마지막으로 그린 뒤 기한이 지났을 수 있다 */
  const pick = (coupon: (typeof usable)[number]) => onChange(isUsableCoupon(coupon) ? coupon.couponIssueId : null);

  if (usable.length === 0) return null;

  const discount = selected ? discountOf(amount, selected.discountRate) : 0;

  return (
    <fieldset className={styles.box} disabled={disabled}>
      <legend className={styles.legend}>
        <Icon name="ticket" size={16} /> 쿠폰
      </legend>
      <label className={styles.option}>
        <input type="radio" name="coupon" checked={value === null} onChange={() => onChange(null)} />
        <span className={styles.name}>쿠폰 사용 안 함</span>
      </label>
      {usable.map((coupon) => (
        <label key={coupon.couponIssueId} className={styles.option}>
          <input
            type="radio"
            name="coupon"
            checked={value === coupon.couponIssueId}
            onChange={() => pick(coupon)}
          />
          <span className={styles.name}>
            {coupon.title}
            <span className={styles.rate}>{coupon.discountRate}%</span>
          </span>
          <span className={styles.save}>−{formatWon(discountOf(amount, coupon.discountRate))}</span>
        </label>
      ))}
      <dl className={styles.total}>
        <div>
          <dt>상품 금액</dt>
          <dd>{formatWon(amount)}</dd>
        </div>
        <div>
          <dt>쿠폰 할인</dt>
          <dd className={discount > 0 ? styles.minus : undefined}>{discount > 0 ? `−${formatWon(discount)}` : "0원"}</dd>
        </div>
        <div className={styles.final}>
          <dt>결제 예정 금액</dt>
          <dd>{formatWon(selected ? finalAmountOf(amount, selected.discountRate) : amount)}</dd>
        </div>
      </dl>
    </fieldset>
  );
}
