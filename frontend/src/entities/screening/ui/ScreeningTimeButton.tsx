import { timeOfIso } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import { remainingSeatsLabel } from "../lib/labels";
import type { Screening } from "../model/types";
import styles from "./ScreeningTimeButton.module.css";

interface Props {
  screening: Screening;
  onSelect: (screening: Screening) => void;
  /** 내 선호 시간대 회차 (USR-05). 무엇이 선호인지는 위에서 정한다 */
  highlighted?: boolean;
}

/** W3 회차 버튼: 시작 시각 · ~종료 · 잔여석. 무엇을 할지(로그인 확인 → 좌석 선택)는 feature가 정한다 */
export function ScreeningTimeButton({ screening, onSelect, highlighted = false }: Props) {
  const start = timeOfIso(screening.startsAt);
  const end = timeOfIso(screening.endsAt);
  const remaining = remainingSeatsLabel(screening);
  return (
    <button
      type="button"
      className={styles.button}
      disabled={!remaining.selectable}
      onClick={() => onSelect(screening)}
      data-preferred={highlighted || undefined}
      aria-label={`${start} 시작 ${end} 종료, ${remaining.text}${highlighted ? ", 선호 시간대" : ""}`}
    >
      {highlighted && <Icon name="heart" size={10} fill="currentColor" className={styles.heart} />}
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
