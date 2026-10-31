import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { bookingDetailQuery, formatAmount, formatCountdown } from "@/entities/booking";
import { BookingSteps } from "@/features/booking-steps";
import { CardForm, POLL_ATTEMPTS, useCountdown, usePayBooking, type PayOutcome } from "@/features/booking-payment";
import { RequireLogin } from "@/features/require-login";
import { useReleaseHold } from "@/features/seat-selection";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { Button, EmptyState, ErrorState, LoadingBlock, PageHeading, useToast } from "@/shared/ui";
import { BookingResult } from "@/widgets/booking-result";
import styles from "./BookingPayPage.module.css";

/**
 * W5 모의 결제 + W6 결과 (BKG-10). 결제는 <b>한 번만</b> 요청한다 —
 * 503으로 결과를 못 받으면 `GET /bookings/{id}`를 2초 간격 최대 5회 확인하고, 다시 결제하지 않는다.
 */
export function BookingPayPage() {
  return (
    <RequireLogin>
      <Pay />
    </RequireLogin>
  );
}

function Pay() {
  const params = useParams();
  const bookingId = Number(params.bookingId);
  const toast = useToast();
  const [outcome, setOutcome] = useState<PayOutcome | null>(null);

  useDocumentTitle("결제");

  const bookingQuery = useQuery({ ...bookingDetailQuery(bookingId), enabled: Number.isInteger(bookingId) });
  const booking = bookingQuery.data ?? null;

  const pay = usePayBooking(bookingId, (result) => {
    setOutcome(result);
    if (result.kind === "error") toast.show(result.message, { tone: "error" });
    // 결과 패널은 서버가 말하는 예매 상태로 그린다 (화면이 추측하지 않는다)
    void bookingQuery.refetch();
  });

  const release = useReleaseHold({
    onReleased: () => {
      toast.show("좌석 선택을 취소했어요.");
      void bookingQuery.refetch();
    },
    onFailed: (message) => toast.show(message, { tone: "error" }),
  });

  const secondsLeft = useCountdown(booking?.status === "PENDING" ? booking.expiresAt : null);

  if (!Number.isInteger(bookingId)) {
    return <EmptyState as="h1" title="잘못된 예매 주소예요" description="상영시간표에서 다시 예매해 주세요." />;
  }

  const seatSelectHref = booking ? routes.screening(booking.screeningId) : routes.showtimes();

  const renderBody = () => {
    if (bookingQuery.isPending) return <LoadingBlock label="예매 정보를 불러오는 중이에요" />;
    if (bookingQuery.isError) {
      return (
        <ErrorState
          error={bookingQuery.error}
          as="h2"
          title="예매 정보를 불러오지 못했어요"
          messages={{
            NOT_FOUND: "예매를 찾을 수 없습니다.",
            FORBIDDEN: "다른 사람의 예매는 볼 수 없습니다.",
          }}
          onRetry={() => void bookingQuery.refetch()}
        />
      );
    }
    if (!booking) return <EmptyState title="예매를 찾을 수 없어요" />;

    // 결과를 받은 뒤 예매를 다시 조회하는 동안은 아직 PENDING으로 보인다 →
    // 그때 결제 폼을 다시 열면 사용자가 두 번째 결제를 시도할 수 있다
    const settling = outcome !== null && outcome.kind !== "error" && bookingQuery.isFetching;

    if (pay.pending || settling) {
      return (
        <div className={styles.waiting} role="status" aria-live="polite">
          <LoadingBlock
            label={
              pay.checking > 0
                ? `결제 결과를 확인하고 있어요 (${pay.checking}/${POLL_ATTEMPTS})`
                : settling
                  ? "결제 결과를 반영하고 있어요"
                  : "결제를 처리하고 있어요"
            }
          />
          <p className={styles.note}>
            창을 닫지 말고 기다려 주세요. 결제는 한 번만 요청되고 중복 결제는 되지 않습니다.
          </p>
        </div>
      );
    }

    if (booking.status !== "PENDING" || outcome?.kind === "unknown") {
      return (
        <BookingResult
          booking={booking}
          outcome={outcome}
          seatSelectHref={seatSelectHref}
          myPageHref={routes.myPage()}
          showtimesHref={routes.showtimes()}
          onRecheck={() => void bookingQuery.refetch()}
        />
      );
    }

    return (
      <div className={styles.pay}>
        <section className={styles.summary} aria-label="예매 내용">
          <dl>
            <div>
              <dt>좌석</dt>
              <dd>{booking.seatLabels.join(", ")}</dd>
            </div>
            <div>
              <dt>좌석 수</dt>
              <dd>{booking.seatCount}석</dd>
            </div>
            <div>
              <dt>결제 금액</dt>
              <dd className={styles.amount}>{formatAmount(booking.amount)}</dd>
            </div>
          </dl>
          <p className={styles.timer} data-urgent={secondsLeft > 0 && secondsLeft <= 60 ? true : undefined}>
            {secondsLeft > 0 ? (
              <>
                남은 시간 <strong>{formatCountdown(secondsLeft)}</strong>
              </>
            ) : (
              <>선택 시간이 거의 끝났어요. 결제가 되지 않으면 좌석을 다시 선택해 주세요.</>
            )}
          </p>
        </section>

        <section className={styles.card} aria-label="결제 정보">
          <CardForm onSubmit={pay.pay} pending={pay.pending} />
          <Button
            variant="ghost"
            block
            onClick={() => release.release(booking.bookingId)}
            disabled={release.pending || pay.pending}
          >
            {release.pending ? "취소 중…" : "좌석 선택 취소"}
          </Button>
        </section>
      </div>
    );
  };

  return (
    <div className={styles.page}>
      <PageHeading
        title={booking?.status === "PENDING" ? "모의 결제" : "결제 결과"}
        description={booking?.status === "PENDING" ? "실제 결제가 아니며 카드 정보는 저장하지 않습니다." : undefined}
        back={booking?.status === "PENDING" ? { to: seatSelectHref, label: "좌석 선택" } : undefined}
      >
        <BookingSteps current={2} />
      </PageHeading>
      {renderBody()}
    </div>
  );
}
