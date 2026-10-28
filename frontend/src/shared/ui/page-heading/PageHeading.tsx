import type { ReactNode } from "react";
import { Link } from "react-router";
import { Icon } from "../icon/Icon";
import styles from "./PageHeading.module.css";

interface Props {
  eyebrow?: string;
  title: string;
  description?: string;
  /** 왼쪽 위 「← 라벨」 링크 */
  back?: { to: string; label: string };
  /** 제목 오른쪽 (예: 예매 단계 표시) */
  children?: ReactNode;
}

export function PageHeading({ eyebrow, title, description, back, children }: Props) {
  return (
    <div className={styles.heading}>
      {back && (
        <Link to={back.to} className={styles.back}>
          <Icon name="arrow-left" />
          {back.label}
        </Link>
      )}
      <div className={styles.row}>
        <div>
          {eyebrow && <p className="eyebrow">{eyebrow}</p>}
          <h1 className={styles.title}>{title}</h1>
          {description && <p className={styles.description}>{description}</p>}
        </div>
        {children}
      </div>
    </div>
  );
}
