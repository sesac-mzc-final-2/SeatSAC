import { addDays, dayOfWeekKo, formatDateKo, isWeekend, todayKst } from "@/shared/lib";
import styles from "./DateTabs.module.css";

interface Props {
  value: string;
  onChange: (date: string) => void;
  /** 오늘부터 며칠 (기본 7일) */
  days?: number;
}

/** 날짜 선택: 오늘부터 7일. 주소로 들어온 날짜가 범위 밖이면 그 날짜도 맨 앞에 보여 준다 */
export function DateTabs({ value, onChange, days = 7 }: Props) {
  const today = todayKst();
  const dates = Array.from({ length: days }, (_, i) => addDays(today, i));
  if (!dates.includes(value)) dates.unshift(value);

  return (
    <div className={styles.list} role="group" aria-label="날짜 선택">
      {dates.map((date) => {
        const isToday = date === today;
        return (
          <button
            key={date}
            type="button"
            className={styles.day}
            aria-pressed={date === value}
            aria-label={`${formatDateKo(date, { withYear: true })}${isToday ? " 오늘" : ""}`}
            data-weekend={isWeekend(date)}
            onClick={() => onChange(date)}
          >
            <span className={styles.dow}>{isToday ? "오늘" : dayOfWeekKo(date)}</span>
            <span className={styles.num}>{Number(date.slice(8, 10))}</span>
          </button>
        );
      })}
    </div>
  );
}
