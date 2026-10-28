import { addDays, dayOfWeekKo, formatDateKo, useTodayKst } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import styles from "./DateTabs.module.css";

interface Props {
  value: string;
  onChange: (date: string) => void;
  /** 오늘부터 며칠 (기본 7일) */
  days?: number;
}

const dowIndex = (iso: string) => new Date(`${iso}T12:00:00+09:00`).getUTCDay();

/** 날짜 선택 칩: 오늘부터 7일. 주소로 들어온 날짜가 범위 밖이면 그 날짜도 맨 앞에 보여 준다 */
export function DateTabs({ value, onChange, days = 7 }: Props) {
  const today = useTodayKst();
  const dates = Array.from({ length: days }, (_, i) => addDays(today, i));
  if (!dates.includes(value)) dates.unshift(value);
  const [year, month] = value.split("-").map(Number);

  return (
    <div role="group" aria-label="날짜 선택">
      <div className={styles.heading}>
        <span className={styles.month}>
          <Icon name="calendar" size={16} />
          {year}년 {month}월
        </span>
        <span className={`micro ${styles.note}`}>
          오늘부터 {days}일간<span className={styles.noteDetail}> · 오늘은 지난 회차 제외 · 시각은 KST</span>
        </span>
      </div>
      <div className={styles.chips}>
        {dates.map((date) => {
          const isToday = date === today;
          const dow = dowIndex(date);
          return (
            <button
              key={date}
              type="button"
              className={styles.chip}
              aria-pressed={date === value}
              aria-label={`${formatDateKo(date, { withYear: true })}${isToday ? " 오늘" : ""}`}
              data-dow={dow}
              onClick={() => onChange(date)}
            >
              <small>{isToday ? "오늘" : dayOfWeekKo(date)}</small>
              <strong>{Number(date.slice(8, 10))}</strong>
              <span>{isToday ? dayOfWeekKo(date) : `${Number(date.slice(5, 7))}월`}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
