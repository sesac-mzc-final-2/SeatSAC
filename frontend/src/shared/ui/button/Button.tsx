import type { ButtonHTMLAttributes } from "react";
import { Link, type LinkProps } from "react-router";
import styles from "./Button.module.css";

export type ButtonVariant = "primary" | "secondary" | "ghost";
export type ButtonSize = "sm" | "md" | "lg";

interface StyleProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
  block?: boolean;
  className?: string;
}

function buttonClassName({ variant = "secondary", size = "md", block, className }: StyleProps): string {
  return [styles.button, styles[variant], size !== "md" ? styles[size] : null, block ? styles.block : null, className]
    .filter(Boolean)
    .join(" ");
}

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & StyleProps;

export function Button({ variant, size, block, className, type = "button", ...rest }: ButtonProps) {
  return <button type={type} className={buttonClassName({ variant, size, block, className })} {...rest} />;
}

export type ButtonLinkProps = LinkProps & StyleProps;

/** 링크지만 버튼처럼 보이는 요소 (페이지 이동은 항상 <a>) */
export function ButtonLink({ variant, size, block, className, ...rest }: ButtonLinkProps) {
  return <Link className={buttonClassName({ variant, size, block, className })} {...rest} />;
}
