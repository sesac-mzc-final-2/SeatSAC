import type { ScreeningMovie } from "@/entities/screening";
import { Icon } from "@/shared/ui";
import styles from "./MovieSelect.module.css";

interface Props {
  /** 그날 그 극장에서 상영하는 영화 */
  options: ScreeningMovie[];
  value: number | null;
  onChange: (movieId: number | null) => void;
  /** 선택값이 options에 없을 때(다른 날짜·극장에서 넘어옴) 보여 줄 제목 */
  missingLabel?: string | null;
}

export function MovieSelect({ options, value, onChange, missingLabel }: Props) {
  const missing = value !== null && !options.some((movie) => movie.movieId === value);
  return (
    <div className={styles.wrap}>
      <select
        className={styles.select}
        aria-label="영화 선택"
        value={value ?? ""}
        onChange={(event) => onChange(event.target.value === "" ? null : Number(event.target.value))}
      >
        <option value="">모든 영화</option>
        {missing && (
          <option value={value}>{missingLabel ?? `선택한 영화 (#${value})`} — 이 날짜 상영 없음</option>
        )}
        {options.map((movie) => (
          <option key={movie.movieId} value={movie.movieId}>
            {movie.title}
          </option>
        ))}
      </select>
      <span className={styles.chevron}>
        <Icon name="chevron-down" />
      </span>
    </div>
  );
}
