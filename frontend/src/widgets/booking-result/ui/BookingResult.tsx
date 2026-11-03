import type { ReactNode } from "react";
import { failureMessage, type Booking } from "@/entities/booking";
import type { MovieDetail } from "@/entities/movie";
import type { Screening } from "@/entities/screening";
import type { PayOutcome } from "@/features/booking-payment";
import { Button, ButtonLink, Icon, type IconName } from "@/shared/ui";
import { BookingSummaryCard } from "./BookingSummaryCard";
import styles from "./BookingResult.module.css";

interface Props {
  booking: Booking;
  screening: Screening | null;
  movie: MovieDetail | null;
  /** 이 화면에서 방금 결제한 결과 (새로고침으로 들어왔으면 없다) */
  outcome: PayOutcome | null;
  seatSelectHref: string;
  myPageHref: string;
  homeHref: string;
  /** 결과를 못 받았을 때 예매 상태를 다시 조회한다 (`GET /bookings/{id}`) */
  onRecheck: () => void;
}

/**
 * W6 결제 결과 (시안). 4가지로 갈린다 — 완료 · 실패 · 시간 초과 · (결과를 못 받은) 확인 필요.
 * 실패에 「다시 결제」 버튼을 두지 않는다 — 예매당 결제는 1회이고 좌석도 이미 풀렸다(ADR-24).
 */
export function BookingResult({ booking, screening, movie, outcome, seatSelectHref, myPageHref, homeHref, onRecheck }: Props) {
  const summary = <BookingSummaryCard booking={booking} screening={screening} movie={movie} />;
  const home = (
    <ButtonLink to={homeHref} variant="ghost" className={styles.textLink}>
      상영중 영화로 돌아가기
    </ButtonLink>
  );
  const reselect = (
    <ButtonLink to={seatSelectHref} variant="primary" block>
      좌석 다시 고르기
      <Icon name="arrow-right" size={16} />
    </ButtonLink>
  );

  // 다시 조회해 결과가 나왔으면(PENDING이 아니면) 아래에서 그 결과를 그린다
  if (outcome?.kind === "unknown" && booking.status === "PENDING") {
    return (
      <ResultLayout
        tone="pending"
        icon="clock"
        eyebrow="잠시만 기다려 주세요"
        title="결제 결과를 확인하지 못했어요"
        description="결제가 처리 중일 수 있어요. 잠시 후 결과를 다시 확인해 주세요. 중복 결제는 되지 않아요."
        summary={summary}
        actions={
          <>
            <Button variant="primary" block onClick={onRecheck}>
              결과 다시 확인
            </Button>
            {home}
          </>
        }
      />
    );
  }

  switch (booking.status) {
    case "CONFIRMED":
      return (
        <ResultLayout
          tone="ok"
          icon="check"
          eyebrow="당신의 영화 시간이 준비됐어요"
          title="예매가 완료됐어요"
          description="스크린이 켜지는 순간, SeatSAC과 함께해요."
          bookingNumber={booking.bookingId}
          summary={summary}
          actions={
            <>
              <ButtonLink to={myPageHref} variant="primary" block>
                예매 내역 보기
                <Icon name="arrow-right" size={16} />
              </ButtonLink>
              {home}
            </>
          }
        />
      );

    case "PAYMENT_FAILED":
      return (
        <ResultLayout
          tone="fail"
          icon="x"
          eyebrow="다시 선택할 수 있어요"
          title="결제에 실패했습니다"
          description={failureMessage(booking.payment?.failureCode)}
          summary={summary}
          actions={
            <>
              {reselect}
              {home}
            </>
          }
        />
      );

    case "EXPIRED":
      return (
        <ResultLayout
          tone="fail"
          icon="clock"
          eyebrow="선점 시간이 만료됐어요"
          title="선점 시간이 지나 예매가 취소됐어요"
          description={
            outcome?.kind === "expired" && outcome.refunded
              ? "결제된 금액은 자동으로 환불 처리했어요. 이 데모에서는 실제 결제·환불이 일어나지 않아요."
              : "좌석 선택 후 5분이 지나 자동으로 취소됐어요. 좌석을 다시 골라 주세요."
          }
          summary={summary}
          actions={
            <>
              {reselect}
              {home}
            </>
          }
        />
      );

    case "CANCELED":
      return (
        <ResultLayout
          tone="fail"
          icon="x"
          eyebrow="다시 선택할 수 있어요"
          title="예매가 취소됐어요"
          description="이 예매는 취소됐어요. 좌석을 다시 골라 주세요."
          summary={summary}
          actions={
            <>
              {reselect}
              {home}
            </>
          }
        />
      );

    case "PENDING":
      return null; // 결제 화면(W5)이 보여 준다
  }
}

/** 결제 요청·결과 확인 중 (시안의 「확인 중」 결과 화면) */
export function PaymentProgress({ label }: { label: string }) {
  return (
    <div className={styles.result} data-tone="pending" role="status" aria-live="polite">
      <div className={styles.icon}>
        <Icon name="loader" size={32} strokeWidth={1.8} className={styles.spin} />
      </div>
      <p className="eyebrow">잠시만 기다려 주세요</p>
      <h1 className={styles.title}>{label}</h1>
      <p className={styles.description}>
        창을 닫지 말고 기다려 주세요. 결제는 한 번만 요청되고 중복 결제는 되지 않아요.
      </p>
    </div>
  );
}

interface LayoutProps {
  tone: "ok" | "fail" | "pending";
  icon: IconName;
  eyebrow: string;
  title: string;
  description: string;
  bookingNumber?: number;
  summary: ReactNode;
  actions: ReactNode;
}

function ResultLayout({ tone, icon, eyebrow, title, description, bookingNumber, summary, actions }: LayoutProps) {
  return (
    <div className={styles.result} data-tone={tone}>
      <div className={styles.icon}>
        <Icon name={icon} size={32} strokeWidth={1.8} />
      </div>
      <p className="eyebrow">{eyebrow}</p>
      <h1 className={styles.title}>{title}</h1>
      <p className={styles.description}>{description}</p>
      {bookingNumber !== undefined && (
        <p className={styles.number}>
          예매 번호<strong>{bookingNumber}</strong>
        </p>
      )}
      <div className={styles.summary}>{summary}</div>
      <div className={styles.actions}>{actions}</div>
      <p className={styles.micro}>비영리 교육용 데모 · 실제 예매·결제가 이루어지지 않습니다.</p>
    </div>
  );
}
