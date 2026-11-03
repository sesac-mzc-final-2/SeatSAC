import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useParams } from "react-router";
import { bookingDetailQuery } from "@/entities/booking";
import { movieDetailQuery } from "@/entities/movie";
import { screeningDetailQuery } from "@/entities/screening";
import { BookingSteps } from "@/features/booking-steps";
import { CardForm, HoldTimer, POLL_ATTEMPTS, useCountdown, usePayBooking, type PayOutcome } from "@/features/booking-payment";
import { RequireLogin } from "@/features/require-login";
import { useReleaseHold } from "@/features/seat-selection";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { EmptyState, ErrorState, LoadingBlock, PageHeading, useToast } from "@/shared/ui";
import { BookingResult, BookingSummaryCard, PaymentProgress } from "@/widgets/booking-result";
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

  // 「예매 정보」 카드용 — 못 받아도 결제는 된다 (좌석·금액은 예매에 있다)
  const screeningQuery = useQuery({
    ...screeningDetailQuery(booking?.screeningId ?? 0),
    enabled: booking !== null,
  });
  const screening = screeningQuery.data ?? null;
  const movieQuery = useQuery({
    ...movieDetailQuery(screening?.movie.movieId ?? 0),
    enabled: screening !== null,
  });
  const movie = movieQuery.data ?? null;

  const pay = usePayBooking(bookingId, (result) => {
    setOutcome(result);
    if (result.kind === "error") toast.show(result.message, { tone: "error" });
    // 결과 패널은 서버가 말하는 예매 상태로 그린다 (화면이 추측하지 않는다)
    void bookingQuery.refetch();
  });

  const release = useReleaseHold({
    onReleased: ({ alreadyEnded }) => {
      toast.show(alreadyEnded ? "이미 끝난 예매예요." : "좌석 선택을 취소했어요.");
      void bookingQuery.refetch();
    },
    onFailed: (message) => toast.show(message, { tone: "error" }),
  });

  const secondsLeft = useCountdown(booking?.status === "PENDING" ? booking.expiresAt : null);

  if (!Number.isInteger(bookingId)) {
    return <EmptyState as="h1" title="잘못된 예매 주소예요" description="상영시간표에서 다시 예매해 주세요." />;
  }

  const seatSelectHref = booking ? routes.screening(booking.screeningId) : routes.showtimes();
  // 결과를 받은 뒤 예매를 다시 조회하는 동안은 아직 PENDING으로 보인다 →
  // 그때 결제 폼을 다시 열면 사용자가 두 번째 결제를 시도할 수 있다
  const settling = outcome !== null && outcome.kind !== "error" && bookingQuery.isFetching;
  // 결과 화면(W6)·진행 화면은 제목 줄 없이 가운데 결과만 보여 준다 (시안)
  const showForm =
    !booking || (booking.status === "PENDING" && outcome?.kind !== "unknown" && !pay.pending && !settling);

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

    if (pay.pending || settling) {
      return (
        <PaymentProgress
          label={
            pay.checking > 0
              ? `결제 결과를 확인하고 있어요 (${pay.checking}/${POLL_ATTEMPTS})`
              : settling
                ? "결제 결과를 반영하고 있어요"
                : "결제를 처리하고 있어요"
          }
        />
      );
    }

    if (booking.status !== "PENDING" || outcome?.kind === "unknown") {
      return (
        <BookingResult
          booking={booking}
          screening={screening}
          movie={movie}
          outcome={outcome}
          seatSelectHref={seatSelectHref}
          myPageHref={routes.myPage()}
          homeHref={routes.home()}
          onRecheck={() => void bookingQuery.refetch()}
        />
      );
    }

    return (
      <>
        <HoldTimer secondsLeft={secondsLeft} />
        <div className={styles.pay}>
          <CardForm
            amount={booking.amount}
            onSubmit={pay.pay}
            pending={pay.pending}
            onCancel={() => release.release(booking.bookingId)}
            cancelling={release.pending}
          />
          <div className={styles.summary}>
            <BookingSummaryCard booking={booking} screening={screening} movie={movie} />
          </div>
        </div>
      </>
    );
  };

  return (
    <div className={styles.page}>
      {showForm && (
        <PageHeading
          eyebrow="자리까지 골랐다면, 이제 마지막 단계"
          title="모의 결제"
          back={{ to: seatSelectHref, label: "좌석 선택" }}
        >
          <BookingSteps current={2} />
        </PageHeading>
      )}
      {renderBody()}
    </div>
  );
}
