import type { Theater } from "@/entities/theater";
import styles from "./TheaterTabs.module.css";

interface Props {
  theaters: Theater[];
  value: number | null;
  onChange: (theaterId: number) => void;
}

/** 극장 선택 (배치 있는 4곳). 지역이 다른 극장은 이름 뒤에 지역을 덧붙인다 */
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
