import { SeatMap, SeatMapLegend, type AuditoriumLayout, type LayoutSeat } from "@/entities/auditorium";
import type { SeatStatus } from "@/entities/booking";
import { screenTypeLabel } from "@/entities/screening";
import { seatRenderState, type SeatChoice } from "@/features/seat-selection";
import { Icon } from "@/shared/ui";
import styles from "./SeatBoard.module.css";

interface Props {
  layout: AuditoriumLayout;
  statusOfSeat: (seatId: number) => SeatStatus;
  choice: SeatChoice;
  onToggle: (seat: LayoutSeat) => void;
  /** 실시간 반영(SSE)이 붙어 있는지 */
  live: boolean;
  /** 판매하는 좌석 수 / 남은 좌석 수 */
  totalSeats: number;
  remaining: number;
  disabled?: boolean;
}

/** W4 좌석 배치도 패널 (시안 「좌석 선택」 왼쪽) — 상영관·좌석 수·실시간 연결 + 배치도 + 범례 */
export function SeatBoard({ layout, statusOfSeat, choice, onToggle, live, totalSeats, remaining, disabled = false }: Props) {
  return (
    <section className={`panel ${styles.board}`} aria-label="좌석 배치도">
      <div className={styles.heading}>
        <span className={styles.room}>
          <Icon name="armchair" size={17} />
          {layout.name} · {screenTypeLabel(layout.screenType)}
          <small>
            총 {totalSeats}석 · 남은 {remaining}석
          </small>
        </span>
        <span className={styles.live} data-live={live || undefined}>
          <i aria-hidden="true" />
          {live ? "실시간 반영 중" : "연결 다시 시도 중"}
        </span>
      </div>

      <div className={styles.mapBox}>
        <SeatMap
          layout={layout}
          stateOf={(seat) =>
            seatRenderState({
              status: statusOfSeat(seat.seatId),
              active: seat.active,
              selected: choice.has(seat.seatId),
            })
          }
          onSelect={onToggle}
          disabled={disabled}
        />
      </div>

      <SeatMapLegend className={styles.legend} />
      <p className={styles.help}>
        <Icon name="info" size={15} />
        좌석은 최대 4석까지 선택할 수 있어요.
      </p>
    </section>
  );
}
