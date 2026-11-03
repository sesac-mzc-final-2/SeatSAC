import { formatAmount, type Booking } from "@/entities/booking";
import { GradeBadge, MoviePoster, type MovieDetail } from "@/entities/movie";
import { screenTypeLabel, type Screening } from "@/entities/screening";
import { formatDateDots, timeOfIso } from "@/shared/lib";
import styles from "./BookingSummaryCard.module.css";

interface Props {
  booking: Booking;
  /** 회차 정보 (못 받으면 좌석·금액만 보여 준다) */
  screening: Screening | null;
  /** 포스터용 (없으면 제목 카드) */
  movie: MovieDetail | null;
}

/** W5·W6 「예매 정보」 카드 (시안) — 영화·극장·관람 일시·좌석·금액 */
export function BookingSummaryCard({ booking, screening, movie }: Props) {
  return (
    <section className={`panel ${styles.card}`} aria-label="예매 정보">
      <h2>예매 정보</h2>
      {screening && (
        <div className={styles.movie}>
          <MoviePoster
            movie={movie ?? { title: screening.movie.title, posterUrl: null }}
            compact
            className={styles.poster}
          />
          <div>
            <GradeBadge grade={screening.movie.viewGrade} size="sm" />
            <h3>{screening.movie.title}</h3>
            {movie?.titleEn && <p>{movie.titleEn}</p>}
          </div>
        </div>
      )}
      <dl>
        {screening && (
          <>
            <div>
              <dt>극장 · 상영관</dt>
              <dd>
                {screening.theater.name} · {screening.auditorium.name}
                <small>{screenTypeLabel(screening.auditorium.screenType)}</small>
              </dd>
            </div>
            <div>
              <dt>관람 일시</dt>
              <dd>
                {formatDateDots(screening.businessDate)}
                <br />
                {timeOfIso(screening.startsAt)} ~ {timeOfIso(screening.endsAt)}
              </dd>
            </div>
          </>
        )}
        <div>
          <dt>선택 좌석</dt>
          <dd>
            {booking.seatLabels.join(", ")}
            <small>{booking.seatCount}명</small>
          </dd>
        </div>
        {booking.payment?.cardLast4 && (
          <div>
            <dt>카드</dt>
            <dd>•••• {booking.payment.cardLast4}</dd>
          </div>
        )}
      </dl>
      <div className={styles.amount}>
        {/* 결제 실패·만료·취소에 「결제 금액」이라 쓰면 청구된 것처럼 읽힌다 */}
        <span>{booking.status === "CONFIRMED" || booking.status === "PENDING" ? "총 결제 금액" : "예매 금액"}</span>
        <strong>{formatAmount(booking.amount)}</strong>
      </div>
    </section>
  );
}
