import { asOfLabel, chatPathLabel, hasResultTable, isTokenFree, type ChatResponse } from "@/entities/ai-chat";
import { routes } from "@/shared/config";
import { formatWon, serverNow, timeOfIso } from "@/shared/lib";
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { Badge, Button, Chip, Icon, TraceCode } from "@/shared/ui";
import { basisLabel, savedLabel, type CacheNote, type PairMatch } from "../lib/cache-pairs";
import { MOVIE_CHOICE_STEP, answerMovieTitles, type ChatLink } from "../lib/preset-actions";
import { hasHoldButtons, isHoldConfirm, isHoldReply, type HoldState } from "../lib/seat-hold";
import { seatLinks } from "../lib/seat-links";
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
  /**
   * 좌석 잡기 버튼(확인·취소·회차)과 그 문장의 「다시 묻기」를 눌러도 되는지 — 지금 유효한 요청의 것일 때만.
   * `expired` 는 유효한 요청인데 기한(2분)이 지난 경우. 좌석 잡기와 상관없는 항목이면 undefined
   */
  hold?: HoldState;
  /** 결제·좌석 화면 링크를 눌렀을 때 (대화창 닫기) */
  onNavigate?: () => void;
  /** 표의 좌석 링크로 좌석 선택 화면에 넘어갈 때 (대화창 닫기) */
  onSeatNavigate?: () => void;
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

const HOLD_EXPIRED_TEXT = "확인 시간이 지났어요. 잡을 회차와 좌석을 다시 말해 주세요.";

/** 기한(ms)이 지났는지 — 지나는 때에 한 번 다시 그린다(확인 기한과 같은 방식, 서버 시각 기준) */
function usePassed(deadline: number | null): boolean {
  const [now, setNow] = useState(() => serverNow());
  useEffect(() => {
    if (deadline === null) return;
    const left = Math.max(0, deadline - serverNow());
    const timer = window.setTimeout(() => setNow(serverNow()), left + 100);
    return () => window.clearTimeout(timer);
  }, [deadline]);
  return deadline !== null && deadline <= now;
}

/**
 * 채팅으로 좌석 잡기 (초안 3.4.19). 선점 성공이면 예매 요약 + 「결제하러 가기」,
 * 못 잡았으면 「좌석 화면에서 고르기」(또는 진행 중인 예매로). 결제는 채팅에서 하지 않는다
 */
function SeatHoldCard({ response, onNavigate }: { response: ChatResponse; onNavigate?: () => void }) {
  const hold = response.seatHold;
  // 결제 기한(선점 만료)이 지나면 결제 링크를 숨긴다 — 그 예매는 이미 풀렸다
  const payUntil = Date.parse(hold?.expiresAt ?? "");
  const payPassed = usePassed(Number.isFinite(payUntil) ? payUntil : null);
  if (!hold) return null;
  // 예매 ID가 없으면(booking 이 진행 중인 예매를 알려 주지 않은 경우 포함) 결제 화면 링크를 만들지 않는다
  const bookingId = typeof hold.bookingId === "number" ? hold.bookingId : null;
  const held = response.path === "HOLD_RESULT" && hold.reason === null && bookingId !== null;
  const activeElsewhere = hold.reason === "ACTIVE_HOLD_EXISTS";
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
      {bookingId !== null && (held || activeElsewhere) && !payPassed && (
        <Link className={styles.link} to={routes.bookingPay(bookingId)} onClick={onNavigate}>
          {held ? "결제하러 가기" : "진행 중인 예매로 가기"}
          {hold.expiresAt && ` (${timeOfIso(hold.expiresAt)}까지)`}
          <Icon name="arrow-right" size={13} />
        </Link>
      )}
      {(held || activeElsewhere) && payPassed && (
        <p className={styles.note} role="status">
          {held
            ? "결제 시간이 지났어요. 잡을 회차와 좌석을 다시 말해 주세요."
            : "진행 중이던 예매의 결제 시간이 지났어요. 잡을 회차와 좌석을 다시 말해 주세요."}
        </p>
      )}
      {activeElsewhere && bookingId === null && (
        <p className={styles.note}>진행 중인 예매는 마이페이지 예매 내역에서 확인해 주세요.</p>
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
  onSeatNavigate,
}: {
  response: ChatResponse;
  demo: EntryDemo | null;
  link?: ChatLink;
  onPickMovie?: (title: string) => void;
  onChoose?: (message: string) => void;
  hold?: Props["hold"];
  onNavigate?: () => void;
  onSeatNavigate?: () => void;
}) {
  const movieTitles = answerMovieTitles(response);
  const choices = response.choices ?? [];
  // 좌석 잡기 확인·취소·회차 고르기: 지금 유효한 요청의 버튼만 누를 수 있다(지난 것·시간이 지난 것은 막는다).
  // 막을지는 응답 값(path·confirmExpiresAt)으로 정한다 — 버튼 문장의 모양은 주/보조 버튼 구분에만 쓴다.
  // 서버도 확인 ID가 맞는 대기 행동이 있을 때만 잡지만, 다른 것을 확정하는 일이 없게 화면에서도 막는다
  const holdButtons = hasHoldButtons(response);
  const canChoose = holdButtons && hold !== "active" ? undefined : onChoose;
  const table = hasResultTable(response);
  // 매진·마감은 이 답을 그린 시각 기준(대화창을 다시 열면 새로 본다). 그 뒤에 마감되면 좌석 화면·선점이 막는다
  const [shownAt] = useState(() => serverNow());
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
        <p className={styles.note} role="status">
          {HOLD_EXPIRED_TEXT}
        </p>
      )}
      <SeatHoldCard response={response} onNavigate={onNavigate} />
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
      {demo && <DemoMeta response={response} note={demo.note} />}
    </div>
  );
}

export function ChatEntryView({ entry, demo, onRetry, onPickMovie, onChoose, hold, onNavigate, onSeatNavigate }: Props) {
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
        hold={hold}
        onNavigate={onNavigate}
        onSeatNavigate={onSeatNavigate}
      />
    );
  }
  // 좌석 잡기 버튼 문장의 「다시 묻기」는 그 요청이 아직 유효할 때만 — 지난 확인이 새 확인을 확정하지 않게.
  // 어느 답의 버튼인지 알 수 없는 확인·취소 문장(hold 없음)도 막는다
  const retryBlocked = hold !== undefined ? hold !== "active" : isHoldReply(entry.question);
  return (
    <div className={styles.error} role="alert">
      <p className={styles.errorText}>
        <Icon name="alert-circle" size={15} />
        {entry.info.message}
      </p>
      {/* 기한이 지나 「다시 묻기」가 사라질 때 — 확인 요청의 답과 같은 안내 */}
      {entry.info.retryable && hold === "expired" && (
        <p className={styles.note} role="status">
          {HOLD_EXPIRED_TEXT}
        </p>
      )}
      <div className={styles.errorActions}>
        {entry.info.retryable && onRetry && !retryBlocked && (
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
