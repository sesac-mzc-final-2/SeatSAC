import type { ReactNode } from "react";
import { Icon, type IconName } from "../icon/Icon";
import styles from "./EmptyState.module.css";

export type HeadingLevel = "h1" | "h2" | "h3";

interface Props {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  icon?: IconName;
  /** 제목 수준. 화면 전체를 이 블록이 차지하면(404 등) h1 로 */
  as?: HeadingLevel;
}

/** 가운데 정렬 빈 상태 (둥근 아이콘 + 제목 + 설명 + 버튼) */
export function EmptyState({ title, description, action, icon = "film", as: Heading = "h3" }: Props) {
  return (
    <div className={styles.box}>
      <div className={styles.icon}>
        <Icon name={icon} size={28} strokeWidth={1.5} />
      </div>
      <Heading className={styles.title}>{title}</Heading>
      {description && <p className={styles.description}>{description}</p>}
      {action && <div className={styles.action}>{action}</div>}
    </div>
  );
}
