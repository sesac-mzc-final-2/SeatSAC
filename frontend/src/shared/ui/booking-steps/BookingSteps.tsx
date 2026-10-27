import { Icon } from "../icon/Icon";
import styles from "./BookingSteps.module.css";

const STEPS = ["상영시간", "좌석 선택", "모의 결제"] as const;

/** 예매 흐름 단계 표시 (W3 → W4 → W5). 좌석·결제 화면(남궁일)도 같은 컴포넌트를 쓴다 */
export function BookingSteps({ current }: { current: 0 | 1 | 2 }) {
  return (
    <ol className={styles.steps} aria-label="예매 단계">
      {STEPS.map((label, index) => (
        <li
          key={label}
          className={[styles.step, index === current ? styles.current : index < current ? styles.done : ""].join(" ")}
          aria-current={index === current ? "step" : undefined}
        >
          <b>{String(index + 1).padStart(2, "0")}</b>
          {label}
          {index < STEPS.length - 1 && <Icon name="chevron-right" size={14} />}
        </li>
      ))}
    </ol>
  );
}
