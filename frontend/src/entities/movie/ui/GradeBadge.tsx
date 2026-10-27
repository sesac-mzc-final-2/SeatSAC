import { gradeLabel, gradeTitle } from "../lib/grade";
import type { ViewGrade } from "../model/types";
import styles from "./GradeBadge.module.css";

/** 관람 등급 원형 배지 (전체 초록 · 12 파랑 · 15 주황 · 청불 빨강) */
export function GradeBadge({ grade, size = "md" }: { grade: ViewGrade; size?: "sm" | "md" }) {
  return (
    <span
      className={[styles.rating, size === "sm" ? styles.sm : ""].join(" ")}
      data-grade={grade}
      title={gradeTitle(grade)}
      aria-label={gradeTitle(grade)}
    >
      {gradeLabel(grade)}
    </span>
  );
}
