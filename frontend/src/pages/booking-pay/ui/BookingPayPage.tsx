import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router";
import { bookingDetailQuery } from "@/entities/booking";
import { movieDetailQuery } from "@/entities/movie";
import { screeningDetailQuery } from "@/entities/screening";
import { BookingSteps } from "@/features/booking-steps";
import { myProfileQuery } from "@/entities/user";
import {
  CardForm,
  HoldTimer,
  POLL_ATTEMPTS,
  TossPayPanel,
  readTossPayReturn,
  useCountdown,
  usePayBooking,
  useTossPay,
  type PayOutcome,
  type TossPayStage,
} from "@/features/booking-payment";
import { RequireLogin } from "@/features/require-login";
import { useReleaseHold } from "@/features/seat-selection";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { EmptyState, ErrorState, LoadingBlock, PageHeading, useToast } from "@/shared/ui";
import { BookingResult, BookingSummaryCard, PaymentProgress } from "@/widgets/booking-result";
import styles from "./BookingPayPage.module.css";

/**
 * W5 결제 + W6 결과 (BKG-10, ADR-112). 화면 결제는 <b>토스페이(포트원)</b>만이고, 모의 카드는 ADMIN에게만 보인다
 * (서버도 ADMIN·테스트 키가 아니면 막는다). 결제는 <b>한 번만</b> 요청한다 —
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
  const location = useLocation();
  const navigate = useNavigate();
  const [outcome, setOutcome] = useState<PayOutcome | null>(null);
  const [showMockCard, setShowMockCard] = useState(false);

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

  // 역할은 토큰을 파싱하지 않고 프로필에서 (W9). 못 받으면 일반 사용자로 본다 — 모의 카드는 관리자 전용
  const profileQuery = useQuery(myProfileQuery());
  const isAdmin = profileQuery.data?.role === "ADMIN";

  const onSettled = (result: PayOutcome) => {
    setOutcome(result);
    if (result.kind === "error") toast.show(result.message, { tone: "error" });
    // 결과 패널은 서버가 말하는 예매 상태로 그린다 (화면이 추측하지 않는다)
    void bookingQuery.refetch();
  };
  const pay = usePayBooking(bookingId, onSettled);
  const tossPay = useTossPay(bookingId, onSettled);
  const paying = pay.pending || tossPay.pending;
  const checking = pay.checking || tossPay.checking;

  // 모바일 결제창이 돌아왔다(`?paymentId=…&code=…`) → 완료 확인만 한 번. 들어온 순간의 주소로 정하고(뒤에서 주소를 지운다),
  // 이 페이지에서는 결제 폼을 다시 열지 않는다 — 같은 예매에 결제를 또 시작하면 안 된다
  const [tossReturn] = useState(() => readTossPayReturn(location.search));
  const returnHandled = useRef(false);
  const completeTossPay = tossPay.complete;
  useEffect(() => {
    if (!tossReturn || returnHandled.current || booking === null) return;
    returnHandled.current = true;
    navigate({ search: "" }, { replace: true });
    if (booking.status === "PENDING") completeTossPay(tossReturn.paymentId, tossReturn.closedMessage);
  }, [tossReturn, booking, navigate, completeTossPay]);

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
  // 그때 결제 폼을 다시 열면 사용자가 두 번째 결제를 시도할 수 있다.
  // 네트워크가 끊겨 재조회가 멈춘(paused) 동안도, 재조회가 실패해 PENDING이 남은 경우도 마찬가지다
  const paymentSent = (outcome !== null && outcome.kind !== "error") || tossReturn !== null;
  const paused = paymentSent && bookingQuery.fetchStatus === "paused";
  const settling = paymentSent && bookingQuery.isFetching;
  // 결과 화면(W6)·진행 화면은 제목 줄 없이 가운데 결과만 보여 준다 (시안)
  const showForm = !booking || (booking.status === "PENDING" && !paymentSent && !paying);

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

    if (paying || settling || paused) {
      return (
        <PaymentProgress
          label={
            paused
              ? "네트워크 연결을 기다리고 있어요"
              : checking > 0
                ? `결제 결과를 확인하고 있어요 (${checking}/${POLL_ATTEMPTS})`
                : settling
                  ? "결제 결과를 반영하고 있어요"
                  : stageLabel(tossPay.stage)
          }
        />
      );
    }

    // 결제를 보낸 뒤에는 폼을 다시 열지 않는다. 아직 PENDING이면(결과 미확인·재조회 실패) 「결과 다시 확인」
    if (booking.status !== "PENDING" || paymentSent) {
      return (
        <BookingResult
          booking={booking}
          screening={screening}
          movie={movie}
          outcome={
            booking.status !== "PENDING" ? outcome : outcome?.kind === "windowClosed" ? outcome : { kind: "unknown" }
          }
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
          <div className={styles.methods}>
            <TossPayPanel
              amount={booking.amount}
              onPay={tossPay.start}
              pending={paying}
              onCancel={() => release.release(booking.bookingId)}
              cancelling={release.pending}
            />
            {isAdmin && (
              <div className={styles.admin}>
                <button
                  type="button"
                  className={styles.adminToggle}
                  aria-expanded={showMockCard}
                  onClick={() => setShowMockCard((open) => !open)}
                >
                  관리자 · 모의 카드로 결제 {showMockCard ? "접기" : "열기"}
                </button>
                {showMockCard && (
                  <CardForm
                    amount={booking.amount}
                    onSubmit={pay.pay}
                    pending={paying}
                    onCancel={() => release.release(booking.bookingId)}
                    cancelling={release.pending}
                  />
                )}
              </div>
            )}
          </div>
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
          title="결제"
          back={{ to: seatSelectHref, label: "좌석 선택" }}
        >
          <BookingSteps current={2} />
        </PageHeading>
      )}
      {renderBody()}
    </div>
  );
}

/** 토스페이 진행 단계별 문구. 결제창이 열려 있는 동안은 창을 끝내 달라고 안내한다 */
function stageLabel(stage: TossPayStage | null): string {
  switch (stage) {
    case "preparing":
      return "결제창을 준비하고 있어요";
    case "window":
      return "토스페이 결제창에서 결제를 마쳐 주세요";
    case "confirming":
      return "토스페이에서 결제 결과를 확인하고 있어요";
    default:
      return "결제를 처리하고 있어요";
  }
}
