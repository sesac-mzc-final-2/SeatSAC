import type { LayoutSeat } from "@/entities/auditorium";
import { MAX_SEATS, amountFor, formatAmount } from "@/entities/booking";
import { Button, Icon } from "@/shared/ui";
import styles from "./SeatChoiceBar.module.css";

interface Props {
  seats: LayoutSeat[];
  /** 좌석을 눌러 뺄 수 있게 */
  onRemove: (seat: LayoutSeat) => void;
  onSubmit: () => void;
  pending: boolean;
  /** 상영이 시작됐거나 이미 선점이 있는 경우 */
  disabled?: boolean;
}

/** W4 아래 고정 바 — 고른 좌석·금액과 「좌석 선택 완료」 */
export function SeatChoiceBar({ seats, onRemove, onSubmit, pending, disabled = false }: Props) {
  const empty = seats.length === 0;
  return (
    <div className={styles.bar}>
      <div className={styles.chosen}>
        <span className={styles.count}>
          {seats.length} / {MAX_SEATS}석
        </span>
        {empty ? (
          <span className={styles.hint}>좌석을 선택해 주세요 (최대 {MAX_SEATS}석)</span>
        ) : (
          <ul className={styles.seats}>
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

      <div className={styles.action}>
        <strong className={styles.amount}>{formatAmount(amountFor(seats.length))}</strong>
        <Button variant="primary" onClick={onSubmit} disabled={empty || pending || disabled}>
          {pending ? "좌석 잡는 중…" : "좌석 선택 완료"}
        </Button>
      </div>
    </div>
  );
}
