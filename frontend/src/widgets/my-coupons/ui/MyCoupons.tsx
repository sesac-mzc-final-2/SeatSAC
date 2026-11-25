import { useQuery } from "@tanstack/react-query";
import { CouponCard, myCouponsQuery } from "@/entities/coupon";
import { CouponEventBanner } from "@/features/coupon-claim";
import { EmptyState, ErrorState, LoadingBlock } from "@/shared/ui";
import styles from "./MyCoupons.module.css";

/** W8 탭 3 내 쿠폰 (CPN-03, 6.3.6): 지금 이벤트 배너 + 받은 쿠폰 전부(받은 시각 내림차순, 쓸 수 없는 것도 상태와 함께) */
export function MyCoupons() {
  const query = useQuery(myCouponsQuery());

  const renderList = () => {
    if (query.isPending) return <LoadingBlock label="쿠폰을 불러오는 중이에요" />;
    if (query.isError) {
      return <ErrorState error={query.error} title="쿠폰을 불러오지 못했어요" onRetry={() => query.refetch()} />;
    }
    if (query.data.items.length === 0) {
      return (
        <div className="panel">
          <EmptyState icon="ticket" title="받은 쿠폰이 없어요" description="VIP 선착순 이벤트가 열리면 여기서 받을 수 있어요." />
        </div>
      );
    }
    return (
      <div className={styles.list}>
        {query.data.items.map((coupon) => (
          <CouponCard key={coupon.couponIssueId} coupon={coupon} />
        ))}
      </div>
    );
  };

  return (
    <div className={styles.wrap}>
      <CouponEventBanner showMyCouponsLink={false} />
      {renderList()}
    </div>
  );
}
