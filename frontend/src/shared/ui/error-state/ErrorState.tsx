import type { ReactNode } from "react";
import { messageForError, traceIdOf } from "@/shared/api";
import { Button } from "../button/Button";
import type { HeadingLevel } from "../empty-state/EmptyState";
import { Icon } from "../icon/Icon";
import { TraceCode } from "../trace-code/TraceCode";
import styles from "./ErrorState.module.css";

interface Props {
  error: unknown;
  title?: string;
  /** 화면별 code → 문구 덮어쓰기 */
  messages?: Record<string, string>;
  onRetry?: () => void;
  action?: ReactNode;
  /** 제목 수준. 화면 전체를 이 블록이 차지하면 h1 로 */
  as?: HeadingLevel;
}

/** 공통 오류 블록: code로 고른 문구 + 다시 시도 + 문의 코드. 남궁일 화면에서도 그대로 쓴다 (W-6) */
export function ErrorState({
  error,
  title = "정보를 불러오지 못했어요",
  messages,
  onRetry,
  action,
  as: Heading = "h3",
}: Props) {
  const traceId = traceIdOf(error);
  return (
    <div className={styles.box} role="alert">
      <div className={styles.icon}>
        <Icon name="alert-circle" size={28} strokeWidth={1.5} />
      </div>
      <Heading className={styles.title}>{title}</Heading>
      <p className={styles.message}>{messageForError(error, messages)}</p>
      {(onRetry || action) && (
        <div className={styles.actions}>
          {onRetry && (
            <Button onClick={onRetry}>
              <Icon name="rotate-cw" />
              다시 시도
            </Button>
          )}
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
