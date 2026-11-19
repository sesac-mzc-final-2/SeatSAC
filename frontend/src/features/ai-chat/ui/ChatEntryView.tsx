import { asOfLabel, chatPathLabel, hasResultTable, isTokenFree, type ChatResponse } from "@/entities/ai-chat";
import { routes } from "@/shared/config";
import { formatWon, timeOfIso } from "@/shared/lib";
import { useState } from "react";
import { Link } from "react-router";
import { Badge, Button, Chip, Icon, TraceCode } from "@/shared/ui";
import { MOVIE_CHOICE_STEP, answerMovieTitles, type ChatLink } from "../lib/preset-actions";
import { hasHoldButtons, isHoldConfirm, isHoldReply } from "../lib/seat-hold";
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
  /**
   * 좌석 잡기 확인·취소 버튼(과 그 문장의 「다시 묻기」)을 눌러도 되는지 — 지금 유효한 확인 요청의 것일 때만.
   * `expired` 는 유효한 확인 요청인데 확인 시간(2분)이 지난 경우
   */
  hold?: "active" | "expired" | "stale";
  /** 결제·좌석 화면 링크를 눌렀을 때 (대화창 닫기) */
  onNavigate?: () => void;
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

/**
 * 채팅으로 좌석 잡기 (초안 3.4.19). 선점 성공이면 예매 요약 + 「결제하러 가기」,
 * 못 잡았으면 「좌석 화면에서 고르기」(또는 진행 중인 예매로). 결제는 채팅에서 하지 않는다
 */
function SeatHoldCard({ response, onNavigate }: { response: ChatResponse; onNavigate?: () => void }) {
  const hold = response.seatHold;
  if (!hold) return null;
  const held = response.path === "HOLD_RESULT" && hold.reason === null && hold.bookingId !== null;
  // 확인 요청·취소·만료에는 링크를 붙이지 않는다 — 버튼(확인/취소)이나 다시 말하기가 다음 행동이다
  const toSeats =
    hold.screeningId !== null &&
    hold.reason !== null &&
    !["CANCELED", "NOT_CONFIRMED", "ACTIVE_HOLD_EXISTS", "SCREENING_STARTED"].includes(hold.reason);
  return (
    <>
      {held && (
        <dl className={styles.hold} aria-label="잡은 좌석">
          <div>
            <dt>좌석</dt>
            <dd>{hold.seatLabels.join(", ")}</dd>
          </div>
          {hold.amount !== null && (
            <div>
              <dt>금액</dt>
              <dd>{formatWon(hold.amount)}</dd>
            </div>
          )}
          {hold.expiresAt && (
            <div>
              <dt>결제 기한</dt>
              <dd>{timeOfIso(hold.expiresAt)}까지</dd>
            </div>
          )}
        </dl>
      )}
      {hold.bookingId !== null && (held || hold.reason === "ACTIVE_HOLD_EXISTS") && (
        <Link className={styles.link} to={routes.bookingPay(hold.bookingId)} onClick={onNavigate}>
          {held ? "결제하러 가기" : "진행 중인 예매로 가기"}
          {hold.expiresAt && ` (${timeOfIso(hold.expiresAt)}까지)`}
          <Icon name="arrow-right" size={13} />
        </Link>
      )}
      {toSeats && hold.screeningId !== null && (
        <Link className={styles.link} to={routes.screening(hold.screeningId)} onClick={onNavigate}>
          좌석 화면에서 고르기
          <Icon name="arrow-right" size={13} />
        </Link>
      )}
    </>
  );
}

function Answer({
  response,
  demo,
  link,
  onPickMovie,
  onChoose,
  hold,
  onNavigate,
}: {
  response: ChatResponse;
  demo: boolean;
  link?: ChatLink;
  onPickMovie?: (title: string) => void;
  onChoose?: (message: string) => void;
  hold?: Props["hold"];
  onNavigate?: () => void;
}) {
  const movieTitles = answerMovieTitles(response);
  const choices = response.choices ?? [];
  // 좌석 잡기 확인·취소: 지금 유효한 확인 요청의 버튼만 누를 수 있다(지난 것·시간이 지난 것은 막는다).
  // 서버도 확인 ID가 맞는 대기 행동이 있을 때만 잡지만, 다른 것을 확정하는 일이 없게 화면에서도 막는다
  const holdButtons = hasHoldButtons(response);
  const canChoose = holdButtons && hold !== "active" ? undefined : onChoose;
  const table = hasResultTable(response);
  return (
    <div className={styles.answer}>
      <p className={styles.text}>{response.answer}</p>
      {response.rejection && <p className={styles.note}>{response.rejection.message}</p>}
      {choices.length > 0 && (
        <div className={styles.choices} role="group" aria-label="답 고르기">
          {choices.map((choice) =>
            holdButtons && isHoldReply(choice.message) ? (
              // 확인은 주 버튼, 취소는 보조 버튼
              <Button
                key={choice.message}
                size="sm"
                variant={isHoldConfirm(choice.message) ? "primary" : "ghost"}
                disabled={!canChoose}
                onClick={() => canChoose?.(choice.message)}
              >
                {choice.label}
              </Button>
            ) : (
              <Chip
                key={choice.message}
                size="sm"
                disabled={!canChoose}
                title={choice.message}
                onClick={() => canChoose?.(choice.message)}
              >
                {choice.label}
              </Chip>
            ),
          )}
        </div>
      )}
      {holdButtons && hold === "expired" && (
        <p className={styles.note}>확인 시간이 지났어요. 잡을 회차와 좌석을 다시 말해 주세요.</p>
      )}
      <SeatHoldCard response={response} onNavigate={onNavigate} />
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
      {demo && <DemoMeta response={response} />}
    </div>
  );
}

export function ChatEntryView({ entry, demo, onRetry, onPickMovie, onChoose, hold, onNavigate }: Props) {
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
        hold={hold}
        onNavigate={onNavigate}
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
        {/* 확인·취소 문장의 「다시 묻기」는 그 확인 요청이 아직 유효할 때만 — 지난 확인이 새 확인을 확정하지 않게 */}
        {entry.info.retryable && onRetry && (!isHoldReply(entry.question) || hold === "active") && (
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
