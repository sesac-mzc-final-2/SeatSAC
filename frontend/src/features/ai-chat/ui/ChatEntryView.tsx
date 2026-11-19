import { asOfLabel, chatPathLabel, hasResultTable, isTokenFree, type ChatResponse } from "@/entities/ai-chat";
import { useState } from "react";
import { Link } from "react-router";
import { Badge, Chip, Icon, TraceCode } from "@/shared/ui";
import { basisLabel, savedLabel, type CacheNote, type PairMatch } from "../lib/cache-pairs";
import { MOVIE_CHOICE_STEP, answerMovieTitles, type ChatLink } from "../lib/preset-actions";
import type { ChatEntry } from "../model/use-ai-chat";
import { ResultTable } from "./ResultTable";
import styles from "./ChatEntryView.module.css";

/**
 * 데모 모드일 때 항목 하나에 넘기는 정보.
 * `note` = 답의 캐시 짝·저장 설명(해당 없으면 없음) · `questionNo` = 질문 말풍선에 붙일 번호(대화 전체 기준)
 */
export interface EntryDemo {
  note?: CacheNote;
  questionNo?: number;
}

interface Props {
  entry: ChatEntry;
  /** 데모 모드가 꺼져 있으면 null — 데모 정보를 그리지 않는다 */
  demo: EntryDemo | null;
  /** 오류 항목의 「다시 묻기」 (보낼 수 없는 상태면 undefined) */
  onRetry?: (question: string, link?: ChatLink) => void;
  /** 답에 나온 영화를 골랐을 때 (보낼 수 없는 상태면 undefined — 버튼 비활성) */
  onPickMovie?: (title: string) => void;
  /** 되묻기(`CLARIFY`) 버튼 — 누르면 그 `message` 를 보낸다 (보낼 수 없는 상태면 undefined) */
  onChoose?: (message: string) => void;
}

/** 응답에 값이 빠졌으면(계약 밖) 숫자 대신 「–」 — 데모 정보 때문에 답 전체가 깨지지 않게 */
const n = (value: number) => (Number.isFinite(value) ? value.toLocaleString("ko-KR") : "–");

/** ai 의 `APP_AI_CACHE_TTL`(기본 1h) — 응답에 TTL 이 없어 화면에 적어 둔 값. ai 설정을 바꾸면 같이 고친다 */
const CACHE_TTL_TEXT = "1시간";

const MATCH_TEXT: Record<PairMatch, string> = {
  same: "같은 질문",
  template: "같은 틀의 질문(말투·값만 다름)",
  semantic: "같은 뜻으로 판단",
};

/**
 * 캐시 설명 (3.4.20). 짝이 있으면 그 답과의 뺄셈(토큰·서버 처리 시간)과 무엇에서 무엇을 뺐는지를,
 * 없으면 숫자 없이 이유만 보여 준다. 화살표는 장식이라 읽지 않게 하고 나머지는 글자 그대로 읽힌다
 */
function CacheNoteLines({ note }: { note: CacheNote }) {
  if (note.kind === "stored") {
    return (
      <p className={styles.dim}>
        이 답의 SQL 틀을 캐시에 저장함 — {CACHE_TTL_TEXT} 동안 같은 틀의 질문은 Claude 없이 답할 수 있습니다
      </p>
    );
  }
  if (note.kind === "outside") {
    return (
      <p className={styles.dim}>
        이 대화에서 짝을 찾지 못한 캐시(이전 대화 · 다른 사용자 · 미리 넣어 둔 질문 · ai 재시작)
      </p>
    );
  }
  const { pair } = note;
  const saved = savedLabel(pair);
  const basis = basisLabel(pair);
  return (
    <>
      <p className={styles.pair}>
        <span aria-hidden="true">↑ </span>
        {pair.questionNo}번째 질문과 {MATCH_TEXT[note.match]}
        {note.match === "semantic" && note.similarity !== null && ` · 유사도 ${note.similarity.toFixed(2)}`}{" "}
        <span className={styles.pairQuestion}>「{pair.question}」</span>
      </p>
      {saved && <p className={styles.saved}>{saved}</p>}
      {basis && <p className={styles.dim}>{basis}</p>}
    </>
  );
}

/** 데모 모드: 처리 경로 · Claude 호출 · 토큰 · 지연 · 캐시 짝 · SQL */
function DemoMeta({ response, note }: { response: ChatResponse; note?: CacheNote }) {
  // llm·latencyMs 는 계약상 항상 있지만, 빠진 응답이 와도 그 칸만 생략한다
  const llm: ChatResponse["llm"] | null | undefined = response.llm;
  const free = Boolean(llm) && isTokenFree(response);
  return (
    <div className={styles.demo} aria-label="처리 정보">
      <div className={styles.demoRow}>
        <Badge tone={free ? "accent" : "neutral"}>{chatPathLabel(response.path)}</Badge>
        {llm && (
          <span className={free ? styles.free : undefined}>
            {free ? "Claude 호출 없음 · 토큰 0" : `Claude ${llm.calls}회 · 입력 ${n(llm.inputTokens)} · 출력 ${n(llm.outputTokens)}`}
          </span>
        )}
        {Number.isFinite(response.latencyMs) && <span className={styles.dim}>{n(response.latencyMs)}ms</span>}
      </div>
      {llm && !free && llm.cacheReadTokens > 0 && (
        <p className={styles.dim}>프롬프트 캐시 읽기 {n(llm.cacheReadTokens)} 토큰 (절감과 별도)</p>
      )}
      {note && <CacheNoteLines note={note} />}
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
}: {
  response: ChatResponse;
  demo: EntryDemo | null;
  link?: ChatLink;
  onPickMovie?: (title: string) => void;
  onChoose?: (message: string) => void;
}) {
  const movieTitles = answerMovieTitles(response);
  const choices = response.choices ?? [];
  const table = hasResultTable(response);
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
        <ResultTable columns={response.columns} rows={response.rows} caption={`결과 ${response.rowCount}건`} />
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
      {demo && <DemoMeta response={response} note={demo.note} />}
    </div>
  );
}

export function ChatEntryView({ entry, demo, onRetry, onPickMovie, onChoose }: Props) {
  if (entry.kind === "question") {
    return (
      <div className={styles.question} data-question="">
        {/* 데모 모드: 「n번째 질문」을 세지 않아도 되게 번호를 붙인다 (숫자만 있는 배지는 읽지 않고 글자로 읽힌다) */}
        {demo?.questionNo !== undefined && (
          <span className={styles.questionNo} aria-hidden="true">
            {demo.questionNo}
          </span>
        )}
        <span className="sr-only">{demo?.questionNo !== undefined ? `내 질문 ${demo.questionNo}번: ` : "내 질문: "}</span>
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
