import styles from "./SeatMapLegend.module.css";

/** 시안 범례 — 좌석 상태 4종 + 리클라이너. 색만으로 구분하지 않도록 이름을 함께 둔다 */
const ITEMS: { key: string; label: string }[] = [
  { key: "available", label: "선택 가능" },
  { key: "selected", label: "내가 선택" },
  { key: "held", label: "다른 사람 선점" },
  { key: "reserved", label: "예매 완료" },
  { key: "recliner", label: "리클라이너" },
];

export function SeatMapLegend({ className }: { className?: string }) {
  return (
    <ul className={[styles.legend, className].filter(Boolean).join(" ")}>
      {ITEMS.map((item) => (
        <li key={item.key}>
          <span className={styles.chip} data-state={item.key} aria-hidden="true" />
          {item.label}
        </li>
      ))}
    </ul>
  );
}
