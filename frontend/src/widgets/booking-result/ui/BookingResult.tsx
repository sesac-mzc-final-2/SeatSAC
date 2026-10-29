import { bookingStatusLabel, failureMessage, formatAmount, type Booking } from "@/entities/booking";
import type { PayOutcome } from "@/features/booking-payment";
import { Badge, ButtonLink, EmptyState } from "@/shared/ui";
import styles from "./BookingResult.module.css";

interface Props {
  booking: Booking;
  /** 이 화면에서 방금 결제한 결과 (새로고침으로 들어왔으면 없다) */
  outcome: PayOutcome | null;
  seatSelectHref: string;
  myPageHref: string;
  showtimesHref: string;
}

/**
 * W6 결제 결과. 4가지로 갈린다 — 완료 · 실패 · 시간 초과 · (결과를 못 받은) 확인 필요.
 * 실패에 「다시 결제」 버튼을 두지 않는다 — 예매당 결제는 1회이고 좌석도 이미 풀렸다(ADR-24).
 */
export function BookingResult({ booking, outcome, seatSelectHref, myPageHref, showtimesHref }: Props) {
  if (outcome?.kind === "unknown") {
    return (
      <EmptyState
        as="h2"
        icon="clock"
        title="결제 결과를 확인하지 못했어요"
        description="결제가 처리 중일 수 있습니다. 잠시 후 예매 내역에서 결과를 확인해 주세요. 중복 결제는 되지 않습니다."
        action={<ButtonLink to={myPageHref} variant="primary">예매 내역에서 확인</ButtonLink>}
      />
    );
  }

  switch (booking.status) {
    case "CONFIRMED":
      return (
        <div className={styles.panel} data-tone="ok">
          <Badge tone="accent">{bookingStatusLabel(booking.status)}</Badge>
          <h2 className={styles.title}>예매가 완료되었어요</h2>
          <dl className={styles.summary}>
            <div>
              <dt>예매 번호</dt>
              <dd>{booking.bookingId}</dd>
            </div>
            <div>
              <dt>좌석</dt>
              <dd>{booking.seatLabels.join(", ")}</dd>
            </div>
            <div>
              <dt>결제 금액</dt>
              <dd>{formatAmount(booking.amount)}</dd>
            </div>
            {booking.payment?.cardLast4 && (
              <div>
                <dt>카드</dt>
                <dd>•••• {booking.payment.cardLast4}</dd>
              </div>
            )}
          </dl>
          <div className={styles.actions}>
            <ButtonLink to={myPageHref} variant="primary">
              예매 내역
            </ButtonLink>
            <ButtonLink to={showtimesHref}>다른 영화 보기</ButtonLink>
          </div>
        </div>
      );

    case "PAYMENT_FAILED":
      return (
        <EmptyState
          as="h2"
          icon="alert-circle"
          title="결제가 완료되지 않았어요"
          description={failureMessage(booking.payment?.failureCode)}
          action={
            <>
              <ButtonLink to={seatSelectHref} variant="primary">
                좌석 다시 선택
              </ButtonLink>
              <ButtonLink to={showtimesHref}>상영시간표로</ButtonLink>
            </>
          }
        />
      );

    case "EXPIRED":
      return (
        <EmptyState
          as="h2"
          icon="clock"
          title="선택 시간이 지났어요"
          description={
            outcome?.kind === "expired" && outcome.refunded
              ? "좌석 선택 후 5분이 지나 자동으로 취소되었습니다. 결제된 금액은 환불 처리했어요."
              : "좌석 선택 후 5분이 지나 자동으로 취소되었습니다. 좌석을 다시 선택해 주세요."
          }
          action={
            <ButtonLink to={seatSelectHref} variant="primary">
              좌석 다시 선택
            </ButtonLink>
          }
        />
      );

    case "CANCELED":
      return (
        <EmptyState
          as="h2"
          icon="x"
          title="취소된 예매예요"
          description="이 예매는 취소되었습니다. 좌석을 다시 선택해 주세요."
          action={
            <ButtonLink to={seatSelectHref} variant="primary">
              좌석 다시 선택
            </ButtonLink>
          }
        />
      );

    case "PENDING":
      return null; // 결제 화면(W5)이 보여 준다
  }
}
