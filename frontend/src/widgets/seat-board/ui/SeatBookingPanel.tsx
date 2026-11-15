import type { LayoutSeat } from "@/entities/auditorium";
import { MAX_SEATS, SEAT_PRICE, amountFor, formatAmount } from "@/entities/booking";
import { GradeBadge, MoviePoster, type MovieDetail } from "@/entities/movie";
import type { Screening } from "@/entities/screening";
import { formatDateDots, timeOfIso } from "@/shared/lib";
import { Button, Icon } from "@/shared/ui";
import styles from "./SeatBookingPanel.module.css";

interface Props {
  screening: Screening;
  /** 포스터용 (없으면 제목 카드) */
  movie: MovieDetail | null;
  seats: LayoutSeat[];
  onRemove: (seat: LayoutSeat) => void;
  onSubmit: () => void;
  pending: boolean;
  disabled?: boolean;
}

/** W4 오른쪽 요약 패널 (시안) — 회차·고른 좌석·금액과 「결제하기」(선점 → 결제 화면) */
export function SeatBookingPanel({ screening, movie, seats, onRemove, onSubmit, pending, disabled = false }: Props) {
  const empty = seats.length === 0;
  return (
    <aside className={`panel ${styles.panel}`} aria-label="예매 요약">
      <div className={styles.movie}>
        <MoviePoster
          movie={movie ?? { title: screening.movie.title, posterUrl: null }}
          compact
          className={styles.poster}
        />
        <div>
          <GradeBadge grade={screening.movie.viewGrade} size="sm" />
          <h3>{screening.movie.title}</h3>
          <span>
            {screening.theater.name} · {screening.auditorium.name}
          </span>
          <p>
            {formatDateDots(screening.businessDate)}
            <br />
            {timeOfIso(screening.startsAt)} ~ {timeOfIso(screening.endsAt)}
          </p>
        </div>
      </div>

      <div className={styles.selection}>
        <h3>
          선택한 좌석
          <span>
            {seats.length} / {MAX_SEATS}
          </span>
        </h3>
        {empty ? (
          <p>좌석을 선택해 주세요.</p>
        ) : (
          <ul className={styles.chips}>
            {seats.map((seat) => (
              <li key={seat.seatId}>
                <button type="button" onClick={() => onRemove(seat)} aria-label={`${seat.seatLabel} 선택 해제`}>
                  {seat.seatLabel}
                  <Icon name="x" size={12} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      <dl className={styles.breakdown}>
        <div>
          <dt>인원</dt>
          <dd>{seats.length}명</dd>
        </div>
        <div>
          <dt>일반 / 리클라이너</dt>
          <dd>1인 {formatAmount(SEAT_PRICE)}</dd>
        </div>
      </dl>
      <div className={styles.total}>
        <span>총 결제 금액</span>
        <strong>{formatAmount(amountFor(seats.length))}</strong>
      </div>

      <Button variant="primary" block onClick={onSubmit} disabled={empty || pending || disabled}>
        {pending ? "좌석 잡는 중…" : "결제하기"}
        {!pending && <Icon name="arrow-right" size={18} />}
      </Button>
      <p className={styles.disclaimer}>
        <Icon name="timer" size={13} />
        결제하기를 누르면 5분 동안 좌석을 지켜 드려요.
        <br />
        토스페이 테스트 결제로 실제 청구되지 않아요.
      </p>
    </aside>
  );
}
