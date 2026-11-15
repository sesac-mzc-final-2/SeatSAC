import styles from "./Logo.module.css";

/** 선점 좌석 심볼(스크린 아래 좌석 중 한 자리가 잎 = 싹) + SeatSAC 워드마크. 직접 그린 심볼, 실제 극장 브랜드와 구별 */
export function Logo({ small = false }: { small?: boolean }) {
  return (
    <span className={[styles.logo, small ? styles.small : ""].join(" ")}>
      <span className={styles.mark} aria-hidden="true">
        <svg viewBox="0 0 48 48">
          <path
            d="M6 10.5Q24 4.5 42 10.5"
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
            strokeLinecap="round"
            opacity=".55"
          />
          <g fill="currentColor">
            <rect x="5" y="17" width="10" height="10" rx="3" />
            <rect x="33" y="17" width="10" height="10" rx="3" />
            <rect x="5" y="31" width="10" height="10" rx="3" />
            <rect x="19" y="31" width="10" height="10" rx="3" />
            <rect x="33" y="31" width="10" height="10" rx="3" />
          </g>
          <path className={styles.leaf} d="M19 27V22a5 5 0 0 1 5-5h5v5a5 5 0 0 1-5 5z" />
          <path className={styles.vein} d="M21.6 24.4 26.4 19.6" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
      </span>
      <span>
        Seat<span>SAC</span>
        <span className={styles.period}>.</span>
      </span>
    </span>
  );
}
