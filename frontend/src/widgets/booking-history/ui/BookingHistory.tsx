import { useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { canCancelBooking, isRetryableBooking, MyBookingCard, myBookingsQuery } from "@/entities/user";
import { CancelBookingButton } from "@/features/cancel-booking";
import { routes } from "@/shared/config";
import { Button, ButtonLink, EmptyState, ErrorState, Icon, LoadingBlock } from "@/shared/ui";
import styles from "./BookingHistory.module.css";

const PAGE_SIZE = 20;

/** USR-04 오류 코드 → 문구 */
const MESSAGES: Record<string, string> = {
  BOOKING_UNAVAILABLE: "예매 서비스가 잠시 응답하지 않아요. 잠시 후 다시 시도해 주세요.",
};

/**
 * W8 탭 1 예매 내역 (USR-04): 상태 배지 4종, 예매 완료 + 상영 전 카드에만 「예매 취소」,
 * 회차를 못 받은 카드는 한 줄 안내. 페이지는 「더 보기」로 이어 붙인다
 */
export function BookingHistory() {
  const query = useInfiniteQuery(myBookingsQuery({ size: PAGE_SIZE }));

  const renderBody = () => {
    if (query.isPending) return <LoadingBlock label="예매 내역을 불러오는 중이에요" />;
    const data = query.data;
    if (!data) {
      return (
        <ErrorState
          error={query.error}
          title="예매 내역을 불러오지 못했어요"
          messages={MESSAGES}
          onRetry={() => query.refetch()}
        />
      );
    }
    const bookings = data.pages.flatMap((page) => page.content);
    const lastPage = data.pages[data.pages.length - 1];
    if (bookings.length === 0) {
      return (
        <div className="panel">
          <EmptyState
            icon="ticket"
            title="아직 예매 내역이 없어요"
            description="마음에 드는 영화를 골라 첫 예매를 해 보세요."
            action={
              <ButtonLink variant="primary" to={routes.showtimes()}>
                상영시간표 보기 <Icon name="arrow-right" size={15} />
              </ButtonLink>
            }
          />
        </div>
      );
    }
    const remaining = lastPage.totalElements - bookings.length;
    return (
      <>
        <div className={styles.list}>
          {bookings.map((booking) => (
            <MyBookingCard
              key={booking.bookingId}
              booking={booking}
              actions={
                canCancelBooking(booking) ? (
                  <CancelBookingButton booking={booking} />
                ) : isRetryableBooking(booking.status) ? (
                  <Link to={routes.showtimes()} className={styles.retry}>
                    다시 상영시간 보기 <Icon name="arrow-right" size={13} />
                  </Link>
                ) : undefined
              }
            />
          ))}
        </div>
        {query.isFetchNextPageError && (
          <div className={styles.pageError}>
            <ErrorState
              error={query.error}
              title="다음 페이지를 불러오지 못했어요"
              messages={MESSAGES}
              onRetry={() => query.fetchNextPage()}
            />
          </div>
        )}
        {query.hasNextPage && !query.isFetchNextPageError && (
          <div className={styles.more}>
            <Button onClick={() => query.fetchNextPage()} disabled={query.isFetchingNextPage}>
              {query.isFetchingNextPage ? "불러오는 중…" : "더 보기"}
              {!query.isFetchingNextPage && remaining > 0 && <span>+{remaining}</span>}
              <Icon name="chevron-right" size={15} />
            </Button>
          </div>
        )}
      </>
    );
  };

  const total = query.data?.pages[0]?.totalElements;

  return (
    <section aria-labelledby="booking-history-title">
      <div className={styles.heading}>
        <div>
          <div className={styles.titleRow}>
            <h2 id="booking-history-title">예매 내역</h2>
            {total !== undefined && <span className={styles.count}>{total}</span>}
          </div>
          <p className={styles.sub}>예매 완료 상태는 상영 시작 전까지 취소할 수 있어요. 시각은 KST.</p>
        </div>
      </div>
      {renderBody()}
    </section>
  );
}
