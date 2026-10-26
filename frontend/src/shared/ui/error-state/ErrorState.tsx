import type { ReactNode } from "react";
import { messageForError, traceIdOf } from "@/shared/api";
import { Button } from "../button/Button";
import { TraceCode } from "../trace-code/TraceCode";
import styles from "./ErrorState.module.css";

interface Props {
  error: unknown;
  title?: string;
  /** 화면별 code → 문구 덮어쓰기 */
  messages?: Record<string, string>;
  onRetry?: () => void;
  action?: ReactNode;
}

/** 공통 오류 블록: code로 고른 문구 + 다시 시도 + 문의 코드. 남궁일 화면에서도 그대로 쓴다 (W-6) */
export function ErrorState({ error, title = "불러오지 못했습니다", messages, onRetry, action }: Props) {
  const traceId = traceIdOf(error);
  return (
    <div className={styles.box} role="alert">
      <div className={styles.text}>
        <strong className={styles.title}>{title}</strong>
        <span className={styles.message}>{messageForError(error, messages)}</span>
      </div>
      {(onRetry || action) && (
        <div className={styles.actions}>
          {onRetry && <Button onClick={onRetry}>다시 시도</Button>}
          {action}
        </div>
      )}
      {traceId && (
        <div className={styles.trace}>
          <TraceCode traceId={traceId} />
        </div>
      )}
    </div>
  );
}
