import { Icon } from "../icon/Icon";
import styles from "./Stepper.module.css";

interface Props {
  /** 단계 이름 (순서대로) */
  steps: readonly string[];
  /** 현재 단계 인덱스 (0부터) */
  current: number;
  label?: string;
}

/** 번호 + 이름으로 진행 단계를 보여 주는 표시 전용 컴포넌트. 어떤 단계가 있는지는 쓰는 쪽(feature)이 정한다 */
export function Stepper({ steps, current, label = "진행 단계" }: Props) {
  return (
    <ol className={styles.steps} aria-label={label}>
      {steps.map((name, index) => (
        <li
          key={name}
          className={[styles.step, index === current ? styles.current : index < current ? styles.done : ""].join(" ")}
          aria-current={index === current ? "step" : undefined}
        >
          <b>{String(index + 1).padStart(2, "0")}</b>
          {name}
          {index < steps.length - 1 && <Icon name="chevron-right" size={14} />}
        </li>
      ))}
    </ol>
  );
}
