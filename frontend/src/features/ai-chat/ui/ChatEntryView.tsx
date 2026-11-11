import { asOfLabel, chatPathLabel, hasResultTable, isTokenFree, type ChatResponse } from "@/entities/ai-chat";
import { Badge, Icon, TraceCode } from "@/shared/ui";
import type { ChatEntry } from "../model/use-ai-chat";
import { ResultTable } from "./ResultTable";
import styles from "./ChatEntryView.module.css";

interface Props {
  entry: ChatEntry;
  demo: boolean;
  /** 오류 항목의 「다시 묻기」 (보낼 수 없는 상태면 undefined) */
  onRetry?: (question: string) => void;
}

const n = (value: number) => value.toLocaleString("ko-KR");

/** 데모 모드: 처리 경로 · Claude 호출 · 토큰 · 지연 · SQL */
function DemoMeta({ response }: { response: ChatResponse }) {
  const { llm } = response;
  const free = isTokenFree(response);
  return (
    <div className={styles.demo} aria-label="처리 정보">
      <div className={styles.demoRow}>
        <Badge tone={free ? "accent" : "neutral"}>{chatPathLabel(response.path)}</Badge>
        <span className={free ? styles.free : undefined}>
          {free ? "Claude 호출 없음 · 토큰 0" : `Claude ${llm.calls}회 · 입력 ${n(llm.inputTokens)} · 출력 ${n(llm.outputTokens)}`}
        </span>
        <span className={styles.dim}>{n(response.latencyMs)}ms</span>
      </div>
      {!free && llm.cacheReadTokens > 0 && (
        <p className={styles.dim}>프롬프트 캐시 읽기 {n(llm.cacheReadTokens)} 토큰 (절감과 별도)</p>
      )}
      {response.rejection && (
        <p className={styles.dim}>
          거부 사유 <code>{response.rejection.reason}</code>
          {response.rejection.object ? ` · ${response.rejection.object}` : ""}
        </p>
      )}
      {response.sql && (
        <details className={styles.sql}>
          <summary>SQL 보기</summary>
          <pre>{response.sql}</pre>
        </details>
      )}
    </div>
  );
}

function Answer({ response, demo }: { response: ChatResponse; demo: boolean }) {
  const table = hasResultTable(response);
  return (
    <div className={styles.answer}>
      <p className={styles.text}>{response.answer}</p>
      {response.rejection && <p className={styles.note}>{response.rejection.message}</p>}
      {table && response.columns && response.rows && (
        <ResultTable columns={response.columns} rows={response.rows} caption={`결과 ${response.rowCount}건`} />
      )}
      <p className={styles.meta}>
        <Icon name="clock" size={12} />
        {asOfLabel(response.asOf)}
        {table && ` · ${n(response.rowCount)}건`}
        {response.truncated && " · 500건까지만 보여요"}
      </p>
      {demo && <DemoMeta response={response} />}
    </div>
  );
}

export function ChatEntryView({ entry, demo, onRetry }: Props) {
  if (entry.kind === "question") {
    return (
      <div className={styles.question} data-question="">
        <span className="sr-only">내 질문: </span>
        {entry.text}
      </div>
    );
  }
  if (entry.kind === "answer") return <Answer response={entry.response} demo={demo} />;
  return (
    <div className={styles.error} role="alert">
      <p className={styles.errorText}>
        <Icon name="alert-circle" size={15} />
        {entry.info.message}
      </p>
      <div className={styles.errorActions}>
        {entry.info.retryable && onRetry && (
          <button type="button" className={styles.retry} onClick={() => onRetry(entry.question)}>
            <Icon name="rotate-cw" size={13} />
            다시 묻기
          </button>
        )}
        {entry.traceId && <TraceCode traceId={entry.traceId} />}
      </div>
    </div>
  );
}
