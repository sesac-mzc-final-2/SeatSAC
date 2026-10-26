import type { ReactNode } from "react";
import styles from "./EmptyState.module.css";

interface Props {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}

export function EmptyState({ title, description, action }: Props) {
  return (
    <div className={styles.box}>
      <div className={styles.text}>
        <strong className={styles.title}>{title}</strong>
        {description && <span className={styles.description}>{description}</span>}
      </div>
      {action}
    </div>
  );
}
