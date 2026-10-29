import { SeatMap, SeatMapLegend, type AuditoriumLayout, type LayoutSeat } from "@/entities/auditorium";
import type { SeatStatus } from "@/entities/booking";
import { SeatChoiceBar, seatRenderState, type SeatChoice } from "@/features/seat-selection";
import { formatDateDots } from "@/shared/lib";
import styles from "./SeatBoard.module.css";

interface Props {
  layout: AuditoriumLayout;
  statusOfSeat: (seatId: number) => SeatStatus;
  choice: SeatChoice;
  onToggle: (seat: LayoutSeat) => void;
  onSubmit: () => void;
  holdPending: boolean;
  /** 실시간 반영(SSE)이 붙어 있는지 */
  live: boolean;
  /** 남은 좌석 수 */
  remaining: number;
  disabled?: boolean;
}

/** W4 좌석 선택 본문 — 배치도 + 범례 + 고른 좌석 바 */
export function SeatBoard({
  layout,
  statusOfSeat,
  choice,
  onToggle,
  onSubmit,
  holdPending,
  live,
  remaining,
  disabled = false,
}: Props) {
  return (
    <div className={styles.board}>
      <div className={styles.top}>
        <p className={styles.remaining}>
          남은 좌석 <strong>{remaining}</strong>석
        </p>
        <p className={styles.live} data-live={live || undefined}>
          <span className={styles.dot} aria-hidden="true" />
          {live ? "실시간 반영 중" : "실시간 연결 끊김 — 다시 연결하는 중"}
        </p>
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

      <SeatChoiceBar
        seats={choice.seats}
        onRemove={onToggle}
        onSubmit={onSubmit}
        pending={holdPending}
        disabled={disabled}
      />

      <p className={styles.source}>
        좌석 배치 출처: 롯데시네마 · {formatDateDots(layout.source.fetchedAt.slice(0, 10))} 수집
      </p>
    </div>
  );
}
