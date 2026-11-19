import { asOfLabel, chatPathLabel, hasResultTable, isTokenFree, type ChatResponse } from "@/entities/ai-chat";
import { useState } from "react";
import { Link } from "react-router";
import { serverNow } from "@/shared/lib";
import { Badge, Chip, Icon, TraceCode } from "@/shared/ui";
import { MOVIE_CHOICE_STEP, answerMovieTitles, type ChatLink } from "../lib/preset-actions";
import { seatLinks } from "../lib/seat-links";
import type { ChatEntry } from "../model/use-ai-chat";
import { ResultTable } from "./ResultTable";
import styles from "./ChatEntryView.module.css";

interface Props {
  entry: ChatEntry;
  demo: boolean;
  /** 오류 항목의 「다시 묻기」 (보낼 수 없는 상태면 undefined) */
  onRetry?: (question: string, link?: ChatLink) => void;
  /** 답에 나온 영화를 골랐을 때 (보낼 수 없는 상태면 undefined — 버튼 비활성) */
  onPickMovie?: (title: string) => void;
  /** 되묻기(`CLARIFY`) 버튼 — 누르면 그 `message` 를 보낸다 (보낼 수 없는 상태면 undefined) */
  onChoose?: (message: string) => void;
  /** 표의 좌석 링크로 좌석 선택 화면에 넘어갈 때 (대화창 닫기) */
  onSeatNavigate?: () => void;
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

/** 답에 나온 영화 고르기. 5개씩 보여 주고 「더 보기」마다 5개씩 아래에 더 붙인다 */
function MovieChoices({ titles, onPick }: { titles: string[]; onPick?: (title: string) => void }) {
  const [shown, setShown] = useState(MOVIE_CHOICE_STEP);
  return (
    <div className={styles.movies} role="group" aria-label="영화 고르기">
      {titles.slice(0, shown).map((title) => (
        <Chip key={title} size="sm" disabled={!onPick} onClick={() => onPick?.(title)}>
          {title}
        </Chip>
      ))}
      {shown < titles.length && (
        <Chip size="sm" className={styles.more} onClick={() => setShown((count) => count + MOVIE_CHOICE_STEP)}>
          더 보기 ({titles.length - shown})
        </Chip>
      )}
    </div>
  );
}

function Answer({
  response,
  demo,
  link,
  onPickMovie,
  onChoose,
  onSeatNavigate,
}: {
  response: ChatResponse;
  demo: boolean;
  link?: ChatLink;
  onPickMovie?: (title: string) => void;
  onChoose?: (message: string) => void;
  onSeatNavigate?: () => void;
}) {
  const movieTitles = answerMovieTitles(response);
  const choices = response.choices ?? [];
  const table = hasResultTable(response);
  // 매진·마감은 이 답을 그린 시각 기준(대화창을 다시 열면 새로 본다). 그 뒤에 마감되면 좌석 화면·선점이 막는다
  const [shownAt] = useState(() => serverNow());
  return (
    <div className={styles.answer}>
      <p className={styles.text}>{response.answer}</p>
      {response.rejection && <p className={styles.note}>{response.rejection.message}</p>}
      {choices.length > 0 && (
        <div className={styles.choices} role="group" aria-label="답 고르기">
          {choices.map((choice) => (
            <Chip
              key={choice.message}
              size="sm"
              disabled={!onChoose}
              title={choice.message}
              onClick={() => onChoose?.(choice.message)}
            >
              {choice.label}
            </Chip>
          ))}
        </div>
      )}
      {/* 0건이면 머리글만 남은 빈 표를 그리지 않는다 — 답 문장이 이유를 말한다 */}
      {table && response.columns && response.rows && response.rows.length > 0 && (
        <ResultTable
          columns={response.columns}
          rows={response.rows}
          caption={`결과 ${response.rowCount}건`}
          seatLinks={seatLinks(response, shownAt)}
          onSeatNavigate={onSeatNavigate}
        />
      )}
      <p className={styles.meta}>
        <Icon name="clock" size={12} />
        {asOfLabel(response.asOf)}
        {table && ` · ${n(response.rowCount)}건`}
        {response.truncated && " · 500건까지만 보여요"}
      </p>
      {movieTitles.length > 0 && <MovieChoices titles={movieTitles} onPick={onPickMovie} />}
      {/* 추천 질문으로 고른 극장·영화·날짜면 그 화면으로 바로 간다 */}
      {link && response.path !== "OUT_OF_SCOPE" && response.path !== "REJECTED" && (
        <Link className={styles.link} to={link.href}>
          {link.label}
          <Icon name="arrow-right" size={13} />
        </Link>
      )}
      {demo && <DemoMeta response={response} />}
    </div>
  );
}

export function ChatEntryView({ entry, demo, onRetry, onPickMovie, onChoose, onSeatNavigate }: Props) {
  if (entry.kind === "question") {
    return (
      <div className={styles.question} data-question="">
        <span className="sr-only">내 질문: </span>
        {entry.text}
      </div>
    );
  }
  if (entry.kind === "answer") {
    return (
      <Answer
        response={entry.response}
        demo={demo}
        link={entry.link}
        onPickMovie={onPickMovie}
        onChoose={onChoose}
        onSeatNavigate={onSeatNavigate}
      />
    );
  }
  return (
    <div className={styles.error} role="alert">
      <p className={styles.errorText}>
        <Icon name="alert-circle" size={15} />
        {entry.info.message}
      </p>
      <div className={styles.errorActions}>
        {entry.info.retryable && onRetry && (
          <button type="button" className={styles.retry} onClick={() => onRetry(entry.question, entry.link)}>
            <Icon name="rotate-cw" size={13} />
            다시 묻기
          </button>
        )}
        {entry.traceId && <TraceCode traceId={entry.traceId} />}
      </div>
    </div>
  );
}
