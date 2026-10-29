import type { SeatRenderState } from "../model/types";
import styles from "./SeatMapLegend.module.css";

/** 범례에 쓸 상태만 — `selected`는 고른 좌석이라 「선택 가능」과 같은 자리에 두면 헷갈린다 */
const ITEMS: { state: SeatRenderState; label: string }[] = [
  { state: "available", label: "선택 가능" },
  { state: "selected", label: "선택한 좌석" },
  { state: "held", label: "다른 사람 선점 중" },
  { state: "reserved", label: "예매 완료" },
  { state: "inactive", label: "판매 안 함" },
];

/** 좌석 색이 무슨 뜻인지. 색만으로 구분하지 않도록 이름을 함께 둔다 */
export function SeatMapLegend({ className }: { className?: string }) {
  return (
    <ul className={[styles.legend, className].filter(Boolean).join(" ")}>
      {ITEMS.map((item) => (
        <li key={item.state}>
          <span className={styles.chip} data-state={item.state} aria-hidden="true" />
          {item.label}
        </li>
      ))}
    </ul>
  );
}
