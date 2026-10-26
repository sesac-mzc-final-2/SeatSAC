import { timeOfIso } from "@/shared/lib";
import { remainingSeatsLabel } from "../lib/labels";
import type { Screening } from "../model/types";
import styles from "./ScreeningTimeButton.module.css";

interface Props {
  screening: Screening;
  onSelect: (screening: Screening) => void;
}

/** W3 회차 버튼: 시작 시각 + 잔여석. 무엇을 할지(로그인 확인 → 좌석 선택)는 feature가 정한다 */
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
      title={`${start} ~ ${end}`}
    >
      <span className={styles.time}>{start}</span>
      <span className={styles.seats} data-tone={remaining.tone}>
        {remaining.text}
      </span>
    </button>
  );
}
