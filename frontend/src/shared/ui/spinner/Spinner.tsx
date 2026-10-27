import styles from "./Spinner.module.css";

export function Spinner({ label = "불러오는 중" }: { label?: string }) {
  return <span className={styles.spinner} role="status" aria-label={label} />;
}

/** 목록·상세가 오기 전 자리 (레이아웃이 튀지 않게 최소 높이) */
export function LoadingBlock({ label = "불러오는 중이에요" }: { label?: string }) {
  return (
    <div className={styles.block} role="status" aria-live="polite">
      <span className={styles.spinner} aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}
