import type { ButtonHTMLAttributes } from "react";
import styles from "./Chip.module.css";

interface Props extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "type"> {
  /** 선택됨 (aria-pressed). 선택 상태는 포인트 컬러 채움 */
  selected?: boolean;
  size?: "md" | "sm";
}

/** 둥근 토글 칩 (선호 극장·시간대·장르, 역할 표시). 선택 가능하면 aria-pressed 로 상태를 알린다 */
export function Chip({ selected, size = "md", className, ...rest }: Props) {
  return (
    <button
      type="button"
      className={[styles.chip, size === "sm" ? styles.sm : null, className].filter(Boolean).join(" ")}
      aria-pressed={selected}
      {...rest}
    />
  );
}
