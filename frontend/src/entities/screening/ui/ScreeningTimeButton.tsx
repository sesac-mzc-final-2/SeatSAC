import { timeOfIso } from "@/shared/lib";
import { remainingSeatsLabel } from "../lib/labels";
import type { Screening } from "../model/types";
import styles from "./ScreeningTimeButton.module.css";

interface Props {
  screening: Screening;
  onSelect: (screening: Screening) => void;
}

/** W3 회차 버튼: 시작 시각 · ~종료 · 잔여석. 무엇을 할지(로그인 확인 → 좌석 선택)는 feature가 정한다 */
export function ScreeningTimeButton({ screening, onSelect }: Props) {
  const start = timeOfIso(screening.startsAt);
  const end = timeOfIso(screening.endsAt);
  const remaining = remainingSeatsLabel(screening);
  return (
    <button
      type="button"
      className={styles.button}
      disabled={!remaining.selectable}
      onClick={() => onSelect(screening)}
      aria-label={`${start} 시작 ${end} 종료, ${remaining.text}`}
    >
      <strong className={styles.time}>{start}</strong>
      <span className={styles.end}>~ {end}</span>
      <small className={styles.seats} data-tone={remaining.tone}>
        {remaining.tone === "ok" || remaining.tone === "low" ? (
          <>
            <b>{screening.remainingSeats}</b> / {screening.totalSeats}석
          </>
        ) : (
          remaining.text
        )}
      </small>
    </button>
  );
}
