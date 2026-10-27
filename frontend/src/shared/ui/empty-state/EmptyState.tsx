import type { ReactNode } from "react";
import { Icon, type IconName } from "../icon/Icon";
import styles from "./EmptyState.module.css";

interface Props {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  icon?: IconName;
}

/** 가운데 정렬 빈 상태 (둥근 아이콘 + 제목 + 설명 + 버튼) */
export function EmptyState({ title, description, action, icon = "film" }: Props) {
  return (
    <div className={styles.box}>
      <div className={styles.icon}>
        <Icon name={icon} size={28} strokeWidth={1.5} />
      </div>
      <h3 className={styles.title}>{title}</h3>
      {description && <p className={styles.description}>{description}</p>}
      {action && <div className={styles.action}>{action}</div>}
    </div>
  );
}
