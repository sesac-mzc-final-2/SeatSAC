import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router";
import { bookingDetailQuery, checkoutQuery } from "@/entities/booking";
import { couponKeys, discountOf, myCouponsQuery } from "@/entities/coupon";
import { movieDetailQuery } from "@/entities/movie";
import { screeningDetailQuery } from "@/entities/screening";
import { BookingSteps } from "@/features/booking-steps";
import {
  HoldTimer,
  TossPayPanel,
  readTossPayReturn,
  useCheckout,
  useCountdown,
  type CheckoutOutcome,
  type CheckoutStage,
  type PayOutcome,
} from "@/features/booking-payment";
import { CouponSelect } from "@/features/coupon-select";
import { RequireLogin } from "@/features/require-login";
import { useReleaseHold } from "@/features/seat-selection";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { EmptyState, ErrorState, Icon, LoadingBlock, PageHeading, useToast } from "@/shared/ui";
import { BookingResult, BookingSummaryCard, PaymentProgress } from "@/widgets/booking-result";
import styles from "./BookingPayPage.module.css";

/**
 * W5 결제 + W6 결과 (BKG-10, ADR-112). 화면 결제는 <b>토스페이(포트원)</b>만이다(모의 결제는 k6·분석 Job 전용).
 *
 * 6.2.5(ADR-116, 명세 v1.5 21장): 결제는 <b>체크아웃 saga</b>로 한다 — 화면은 그대로이고 부르는 API만 바뀐다.
 * `POST …/checkout`(202) → `GET …/checkout` 1초 polling(최대 30초) → `READY_TO_PAY`면 결제창 → `POST …/checkout/confirm` → polling.
 * 쿠폰 거절·결제창 취소(재결제 가능)는 이 화면 안 문구로 알리고 같은 예매로 다시 결제한다(새 멱등 키).
 * 화면은 동기 결제(`/payments`·`/pay`)를 부르지 않는다(동기 경로는 k6·테스트 전용, D19) —
 * 체크아웃 경로가 없으면(플래그 off → 404) 결제 서비스 문제로 안내하고, 돈은 움직이지 않는다.
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
  const queryClient = useQueryClient();
  /** 고른 쿠폰 (`couponIssueId`) — 쿠폰 없이 결제면 null */
  const [couponIssueId, setCouponIssueId] = useState<string | null>(null);
  /** 쿠폰 거절·재결제 가능·결제 서비스 문제 안내 — 예매·좌석은 그대로라 같은 화면에서 다시 결제한다 */
  const [notice, setNotice] = useState<{ message: string; retryable: boolean } | null>(null);

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

  // 결제할 쿠폰의 할인율 — 결제 버튼·패널에 예상 금액을 보여 준다(확정 금액은 서버가 체크아웃에서 정한다)
  const coupons = useQuery(myCouponsQuery());
  const selectedCoupon = coupons.data?.items.find((coupon) => coupon.couponIssueId === couponIssueId) ?? null;

  // 끝난 체크아웃 예매의 확정 금액(쿠폰 할인 뒤) — 결과 화면 요약 카드가 원가를 「총 결제 금액」으로 보이지 않게(#206 리뷰)
  const checkoutDone = booking !== null && booking.status === "CONFIRMED" && Boolean(booking.checkout);
  const finalCheckout = useQuery({ ...checkoutQuery(bookingId), enabled: checkoutDone });

  const onCheckoutSettled = (result: CheckoutOutcome) => {
    // 쿠폰 상태가 바뀌었을 수 있다(예약·사용·반환) — 내 쿠폰 목록을 다시 읽는다
    void queryClient.invalidateQueries({ queryKey: couponKeys.all });
    if (result.kind === "again" || result.kind === "error") {
      // 결제 화면에 남아 다시 결제한다(돈은 움직이지 않았다) — 문구는 화면 안에, 오류는 토스트로도
      // 오류는 기본적으로 다시 누를 수 있다 — 재시도 한도·마감처럼 같은 오류가 반복되는 것만 버튼을 잠근다(retryable: false)
      setNotice({ message: result.message, retryable: result.kind === "error" ? result.retryable !== false : result.retryable });
      if (result.kind === "error") toast.show(result.message, { tone: "error" });
      // 결제창 취소 뒤 쿠폰 반환은 비동기라 잠시 「쓸 수 없음」으로 보여 목록에서 빠질 수 있다(#206 리뷰 보충)
      if (result.kind === "again" && result.state === "PAYMENT_RETRYABLE" && couponIssueId !== null) {
        setNotice({ message: `${result.message} 쿠폰은 돌려받는 데 잠시 걸려요 — 목록에 없으면 잠시 뒤 다시 고르거나 쿠폰 없이 결제해 주세요.`, retryable: result.retryable });
      }
      if (result.kind === "again" && result.state === "COUPON_REJECTED") setCouponIssueId(null);
      void bookingQuery.refetch();
      return;
    }
    setOutcome(result);
    // 결과 패널은 서버가 말하는 예매 상태로 그린다 (화면이 추측하지 않는다)
    void bookingQuery.refetch();
  };
  const checkout = useCheckout(bookingId, onCheckoutSettled);
  const paying = checkout.pending;

  const startPay = () => {
    setNotice(null);
    checkout.start(couponIssueId);
  };

  // 모바일 결제창이 돌아왔다(`?paymentId=…&code=…`) → 완료 확인만 한 번. 들어온 순간의 주소로 정하고(뒤에서 주소를 지운다),
  // 이 페이지에서는 결제 폼을 다시 열지 않는다 — 같은 예매에 결제를 또 시작하면 안 된다
  const [tossReturn] = useState(() => readTossPayReturn(location.search));
  const returnHandled = useRef(false);
  const completeCheckout = checkout.complete;
  const resumeCheckout = checkout.resume;
  useEffect(() => {
    if (!tossReturn || returnHandled.current || booking === null) return;
    returnHandled.current = true;
    navigate({ search: "" }, { replace: true });
    // 체크아웃으로 연 결제창 — 확인 요청 + polling (체크아웃이 없으면 아래 결과 화면이 예매 상태로 그린다)
    if (booking.status === "PENDING" && booking.checkout) completeCheckout(tossReturn.paymentId, tossReturn.closedMessage);
  }, [tossReturn, booking, navigate, completeCheckout]);

  // 새로고침으로 들어왔는데 이 예매에 체크아웃이 있다 → 새로 시작하지 않고 그 체크아웃을 이어서 본다(한 번만)
  const resumed = useRef(false);
  useEffect(() => {
    if (resumed.current || tossReturn || booking === null) return;
    resumed.current = true;
    if (booking.status === "PENDING" && booking.checkout) resumeCheckout();
  }, [tossReturn, booking, resumeCheckout]);

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
  const paymentSent = outcome !== null || tossReturn !== null;
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
              : settling
                ? "결제 결과를 반영하고 있어요"
                : checkoutStageLabel(checkout.stage)
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
          pricing={checkoutDone ? (finalCheckout.data?.amount ?? null) : null}
          onRecheck={() => {
            // 체크아웃 예매는 체크아웃을 다시 이어서 본다(결과가 나오면 예매도 다시 읽는다)
            if (booking.checkout && booking.status === "PENDING") resumeCheckout();
            else void bookingQuery.refetch();
          }}
        />
      );
    }

    // 고른 쿠폰의 예상 할인 — 결제 패널·요약 카드가 같은 금액을 보인다(확정 금액은 서버가 체크아웃에서 정한다)
    const discount = selectedCoupon ? discountOf(booking.amount, selectedCoupon.discountRate) : 0;
    const payAmount = booking.amount - discount;

    return (
      <>
        <HoldTimer secondsLeft={secondsLeft} />
        {notice && (
          <div className={styles.notice} role="alert">
            <Icon name="info" size={18} />
            <p>
              {notice.message}
              {!notice.retryable && " 더는 다시 결제할 수 없어요 — 선점을 취소하고 좌석을 다시 골라 주세요."}
            </p>
          </div>
        )}
        <div className={styles.pay}>
          <div className={styles.main}>
            <CouponSelect amount={booking.amount} value={couponIssueId} onChange={setCouponIssueId} disabled={paying} />
            <TossPayPanel
              amount={payAmount}
              onPay={startPay}
              pending={paying}
              payDisabled={notice !== null && !notice.retryable}
              onCancel={() => release.release(booking.bookingId)}
              cancelling={release.pending}
            />
          </div>
          <div className={styles.summary}>
            <BookingSummaryCard
              booking={booking}
              screening={screening}
              movie={movie}
              pricing={{ original: booking.amount, discount, final: payAmount }}
            />
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

/** 체크아웃 진행 단계별 문구 */
function checkoutStageLabel(stage: CheckoutStage | null): string {
  switch (stage) {
    case "starting":
      return "결제를 시작하고 있어요";
    case "window":
      return "토스페이 결제창에서 결제를 마쳐 주세요";
    case "confirming":
      return "토스페이에서 결제 결과를 확인하고 있어요";
    default:
      return "결제를 준비하고 있어요";
  }
}
