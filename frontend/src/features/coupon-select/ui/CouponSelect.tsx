import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import { discountOf, finalAmountOf, isUsableCoupon, myCouponsQuery } from "@/entities/coupon";
import { formatWon } from "@/shared/lib";
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
  const usable = (coupons.data?.items ?? []).filter((coupon) => isUsableCoupon(coupon));
  const selected = usable.find((coupon) => coupon.couponIssueId === value) ?? null;

  // 고른 쿠폰이 더는 쓸 수 없게 됐으면(만료·다른 결제에 예약) 선택을 푼다
  useEffect(() => {
    if (value !== null && coupons.isSuccess && selected === null) onChange(null);
  }, [value, coupons.isSuccess, selected, onChange]);

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
            onChange={() => onChange(coupon.couponIssueId)}
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
