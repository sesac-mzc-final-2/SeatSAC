import type { ReactNode } from "react";
import { dateOfIso, formatDateKo, formatWon, timeOfIso } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import type { MyBooking } from "../model/types";
import { BookingStatusBadge } from "./BookingStatusBadge";
import styles from "./MyBookingCard.module.css";

interface Props {
  booking: MyBooking;
  /** 오른쪽 아래 행동 (예매 취소 버튼·다시 고르기 링크). 기능 계층이 넣는다 */
  actions?: ReactNode;
}

/**
 * 예매 내역 카드 (W8). 내역 응답에는 포스터가 없어(USR-04 summaries) 왼쪽은 좌석 심볼 자리로 둔다.
 * 회차 정보를 못 받으면(screening null) 상세 줄 대신 「상영 정보를 불러오지 못했어요」 한 줄
 */
export function MyBookingCard({ booking, actions }: Props) {
  const { screening } = booking;
  const canceled = booking.status === "CANCELED";
  const title = screening?.movieTitle ?? `예매 ${booking.bookingId}`;

  return (
    <article className={styles.card} data-canceled={canceled || undefined} aria-label={title}>
      <div className={styles.stub} aria-hidden="true">
        <Icon name="armchair" size={22} strokeWidth={1.4} />
        <span>{booking.seatLabels.length > 0 ? booking.seatLabels.join(" ") : "SeatSAC"}</span>
      </div>

      <div className={styles.body}>
        <div className={styles.topline}>
          <BookingStatusBadge status={booking.status} />
          <span className="micro">예매번호 {booking.bookingId}</span>
        </div>
        <h3 className={styles.title}>{title}</h3>
        {screening ? (
          <dl className={styles.details}>
            <div>
              <dt>
                <Icon name="map-pin" size={15} />
                <span className="sr-only">극장</span>
              </dt>
              <dd>
                {screening.theaterName} · {screening.auditoriumName}
              </dd>
            </div>
            <div>
              <dt>
                <Icon name="calendar" size={15} />
                <span className="sr-only">상영 시각</span>
              </dt>
              <dd>
                {formatDateKo(dateOfIso(screening.startsAt))} · {timeOfIso(screening.startsAt)}–
                {timeOfIso(screening.endsAt)}
              </dd>
            </div>
            <div>
              <dt>
                <Icon name="ticket" size={15} />
                <span className="sr-only">좌석</span>
              </dt>
              <dd>{booking.seatLabels.length > 0 ? booking.seatLabels.join(", ") : "좌석 정보 없음"}</dd>
            </div>
          </dl>
        ) : (
          <p className={styles.missing}>
            <Icon name="alert-circle" size={15} />
            상영 정보를 불러오지 못했어요
          </p>
        )}
      </div>

      <div className={styles.side}>
        <strong className={styles.amount}>{formatWon(booking.amount)}</strong>
        {actions && <div className={styles.actions}>{actions}</div>}
      </div>
    </article>
  );
}
