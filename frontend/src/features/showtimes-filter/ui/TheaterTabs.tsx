import type { Theater } from "@/entities/theater";
import styles from "./TheaterTabs.module.css";

interface Props {
  theaters: Theater[];
  value: number | null;
  onChange: (theaterId: number) => void;
}

/** 극장 선택 (배치 있는 4곳). 버튼에는 이름만(와이어프레임 W3), 지역은 title 로 보조 표시 */
export function TheaterTabs({ theaters, value, onChange }: Props) {
  return (
    <div className={styles.group} role="group" aria-label="극장 선택">
      <span className={styles.label}>극장</span>
      <div className={styles.list}>
        {theaters.map((theater) => (
          <button
            key={theater.theaterId}
            type="button"
            className={styles.pill}
            aria-pressed={theater.theaterId === value}
            onClick={() => onChange(theater.theaterId)}
            title={theater.region ?? undefined}
          >
            {theater.name}
          </button>
        ))}
      </div>
    </div>
  );
}
