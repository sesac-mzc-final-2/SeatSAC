import type { HTMLAttributes } from "react";
import styles from "./Badge.module.css";

export type BadgeTone = "neutral" | "muted" | "accent";

export function Badge({
  tone = "neutral",
  className,
  ...rest
}: HTMLAttributes<HTMLSpanElement> & { tone?: BadgeTone }) {
  return <span className={[styles.badge, className].filter(Boolean).join(" ")} data-tone={tone} {...rest} />;
}
