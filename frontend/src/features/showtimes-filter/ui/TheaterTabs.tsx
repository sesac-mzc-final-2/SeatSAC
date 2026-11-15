import type { Theater } from "@/entities/theater";
import { Icon } from "@/shared/ui";
import styles from "./TheaterTabs.module.css";

interface Props {
  theaters: Theater[];
  value: number | null;
  onChange: (theaterId: number) => void;
  /** 마이페이지 선호 극장 (USR-05). 탭 순서는 그대로 두고 하트만 붙인다 */
  preferredTheaterIds?: readonly number[];
}

/** 극장 선택 탭 (배치 있는 4곳). 버튼에는 이름만(와이어프레임 W3), 지역은 title 로 보조 표시 */
export function TheaterTabs({ theaters, value, onChange, preferredTheaterIds = [] }: Props) {
  return (
    <div role="group" aria-label="극장 선택">
      <div className={styles.label}>
        <Icon name="map-pin" size={17} />
        <h2>극장 선택</h2>
        <span className={styles.count}>{theaters.length}개 극장</span>
      </div>
      <div className={styles.tabs}>
        {theaters.map((theater) => {
          const preferred = preferredTheaterIds.includes(theater.theaterId);
          const hint = [theater.region, preferred ? "선호 극장" : null].filter(Boolean).join(" · ");
          return (
            <button
              key={theater.theaterId}
              type="button"
              className={styles.tab}
              aria-pressed={theater.theaterId === value}
              aria-label={preferred ? `${theater.name}, 선호 극장` : undefined}
              onClick={() => onChange(theater.theaterId)}
              title={hint || undefined}
            >
              {preferred && <Icon name="heart" size={13} fill="currentColor" className={styles.heart} />}
              {theater.name}
            </button>
          );
        })}
      </div>
    </div>
  );
}
