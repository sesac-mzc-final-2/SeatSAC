import styles from "./Logo.module.css";

/** 좌석 심볼 + SeatSAC 워드마크 (직접 그린 심볼, 실제 극장 브랜드와 구별) */
export function Logo({ small = false }: { small?: boolean }) {
  return (
    <span className={[styles.logo, small ? styles.small : ""].join(" ")}>
      <span className={styles.mark} aria-hidden="true">
        <svg viewBox="0 0 28 28" fill="none">
          <path d="M7 15V7.5A3.5 3.5 0 0 1 10.5 4h7A3.5 3.5 0 0 1 21 7.5V15" stroke="currentColor" strokeWidth="2.4" />
          <path
            d="M4 13v5a3 3 0 0 0 3 3h14a3 3 0 0 0 3-3v-5M7 15h14M8 21v3M20 21v3"
            stroke="currentColor"
            strokeWidth="2.4"
            strokeLinecap="round"
          />
        </svg>
      </span>
      <span>
        Seat<span>SAC</span>
        <span className={styles.period}>.</span>
      </span>
    </span>
  );
}
