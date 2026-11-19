import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useMemo, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import type { ChatPreset } from "@/entities/ai-chat";
import { nowShowingMoviesQuery } from "@/entities/movie";
import { theaterListQuery } from "@/entities/theater";
import { serverNow, todayKst } from "@/shared/lib";
import { Button, Chip, Icon } from "@/shared/ui";
import { cacheNotes, demoTotals, questionNumbers } from "../lib/cache-pairs";
import {
  PICK_PROMPTS,
  answerMovieTitles,
  movieFromAnswer,
  dateChoices,
  gradeChoices,
  kstHour,
  movieChoices,
  presetAction,
  theaterChoices,
  type ChatLink,
  type PickKind,
  type PresetChoice,
} from "../lib/preset-actions";
import { activeHold, holdMessages, holdStateOf } from "../lib/seat-hold";
import { MAX_MESSAGE_LENGTH, useAiChat } from "../model/use-ai-chat";
import { useDemoMode } from "../model/use-demo-mode";
import { ChatEntryView } from "./ChatEntryView";
import { ChoicePicker } from "./ChoicePicker";
import styles from "./ChatPanel.module.css";

interface Props {
  /** 대화 상태는 Dock 이 들고 있다 — 창을 닫았다 열어도 이어진다 */
  chat: ReturnType<typeof useAiChat>;
  /** 작성 중인 질문도 Dock 이 들고 있다 — Escape·닫기로 창을 닫아도 남는다 */
  draft: string;
  onDraftChange: (draft: string) => void;
  presets: ChatPreset[];
  /** 추천 질문 API 응답 대기 중 — 플래그 off 를 아직 모르니 입력은 받되 전송만 막는다 */
  checking: boolean;
  /** 추천 질문 API 가 404(gateway 플래그 off) — 「준비 중」 안내만, 닫으면 Dock 이 버튼을 숨긴다 */
  notReady: boolean;
  /** 추천 질문 API 가 503 등으로 실패 — 챗봇을 비활성 안내로 */
  unavailable: boolean;
  onRetryAvailability: () => void;
  retryingAvailability: boolean;
  onClose: () => void;
}

/** 아래 추천 질문 줄에 보이는 버튼 수. 나머지는 「…」로 연다(대화창 폭에서 잘리지 않게) */
const BAR_PRESETS = 3;

const n = (value: number) => value.toLocaleString("ko-KR");
/** 합계의 토큰이 무엇을 세는지 (title · 스크린리더) */
const TOKEN_SCOPE = "프롬프트 캐시 읽기·쓰기 토큰은 포함하지 않습니다";

type Picker = { kind: PickKind; presetId: string } | { kind: "more" };

/** 영화 버튼으로 보낸 질문이 실패했으면 「다시 묻기」도 같은 `continueFrom` 을 보낸다 — 조건이 빠지지 않게(3.4.22) */
const retryContinueFrom = (entry: ReturnType<typeof useAiChat>["entries"][number]): string | undefined =>
  entry.kind === "error" ? entry.continueFrom : undefined;

/** 우하단 대화창 본문 (AI-01·AI-04). 열려 있는 동안만 마운트된다 */
export function ChatPanel({
  chat,
  draft,
  onDraftChange: setDraft,
  presets,
  checking,
  notReady,
  unavailable,
  onRetryAvailability,
  retryingAvailability,
  onClose,
}: Props) {
  const titleId = useId();
  const demo = useDemoMode();
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const logRef = useRef<HTMLDivElement>(null);
  const { entries, ask, restart, isPending, cooldownSeconds, countAfterId, recount } = chat;
  // 데모 모드일 때만 캐시 짝을 찾는다 (꺼져 있으면 아무것도 계산·표시하지 않는다)
  const notes = useMemo(() => (demo.enabled ? cacheNotes(entries) : null), [demo.enabled, entries]);
  const numbers = useMemo(() => (demo.enabled ? questionNumbers(entries) : null), [demo.enabled, entries]);
  const totals = useMemo(
    () => (notes ? demoTotals(entries, notes, countAfterId) : null),
    [notes, entries, countAfterId],
  );
  const [picker, setPicker] = useState<Picker | null>(null);
  // 고르는 목록은 그 버튼을 눌렀을 때만 부른다
  const theaters = useQuery({ ...theaterListQuery(), enabled: picker?.kind === "theater" });
  const movies = useQuery({
    ...nowShowingMoviesQuery(),
    // 영화 고르기 말풍선, 또는 답에 영화가 여럿 나와 고르는 버튼이 있을 때(상영시간표 바로가기에 movieId 가 필요)
    enabled:
      picker?.kind === "movie" ||
      entries.some((entry) => entry.kind === "answer" && answerMovieTitles(entry.response).length > 0),
  });

  // 좌석 잡기: 지금 눌러도 되는 버튼(가장 최근 답의 확인·취소·회차)과 그 기한(2분). 지나면 버튼을 막는다
  const pendingHold = activeHold(entries);
  const knownHoldMessages = holdMessages(entries);
  const [clock, setClock] = useState(() => serverNow());
  const holdExpiresAt = pendingHold?.expiresAt ?? null;
  useEffect(() => {
    if (holdExpiresAt === null) return;
    // 기한이 지나는 때에 한 번 다시 그린다(이미 지났으면 곧바로)
    const left = Math.max(0, holdExpiresAt - serverNow());
    const timer = window.setTimeout(() => setClock(serverNow()), left + 100);
    return () => window.clearTimeout(timer);
  }, [holdExpiresAt]);
  const holdOf = (entry: (typeof entries)[number]) => holdStateOf(entry, pendingHold, knownHoldMessages, clock);

  const disabled = notReady || unavailable;
  const blocked = disabled || checking || isPending || cooldownSeconds > 0;
  const trimmed = draft.trim();
  const tooLong = draft.length > MAX_MESSAGE_LENGTH;

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // 새 질문을 보내면 마지막 질문이 창 맨 위에 오게 — 긴 답(결과 표)도 첫 줄부터 읽힌다. 질문 높이가 창보다 작으면 맨 아래로
  useEffect(() => {
    const log = logRef.current;
    if (!log) return;
    const questions = log.querySelectorAll<HTMLElement>("[data-question]");
    const last = questions[questions.length - 1];
    log.scrollTop = last ? Math.min(last.offsetTop - 12, log.scrollHeight) : log.scrollHeight;
  }, [entries.length, isPending]);

  // 고르는 말풍선이 뜨면 보이게 맨 아래로
  useEffect(() => {
    if (picker && logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight;
  }, [picker, theaters.isSuccess, movies.isSuccess]);

  const send = (text: string, link?: ChatLink, continueFrom?: string) => {
    if (ask(text, link, continueFrom)) {
      setDraft("");
      setPicker(null);
      inputRef.current?.focus();
    }
  };

  /** 고르는 말풍선을 고르지 않고 닫는다. 초점을 가진 버튼이 같이 사라지므로 입력창으로 옮긴다(#142 리뷰) */
  const closePicker = () => {
    setPicker(null);
    inputRef.current?.focus();
  };

  const choosePreset = (preset: ChatPreset) => {
    const action = presetAction(preset, kstHour());
    if (action.kind === "send") {
      send(action.text);
      return;
    }
    if (action.kind === "pick") {
      setPicker({ kind: action.pick, presetId: preset.presetId });
      return;
    }
    // 시각·개수는 입력창에 채우고 그 숫자를 선택해 둔다 — 고쳐서 보낸다
    setPicker(null);
    setDraft(action.text);
    requestAnimationFrame(() => {
      const input = inputRef.current;
      if (!input) return;
      const start = action.text.indexOf(action.select);
      input.focus();
      input.setSelectionRange(start, start + action.select.length);
    });
  };

  // 고르는 말풍선에 보일 선택지 (null = 불러오는 중)
  let choices: PresetChoice[] | null = [];
  let choicesFailed = false;
  if (picker?.kind === "theater") {
    choices = theaters.data ? theaterChoices(theaters.data.items, todayKst()) : null;
    choicesFailed = theaters.isError;
  } else if (picker?.kind === "movie") {
    choices = movies.data ? movieChoices(picker.presetId, movies.data.content) : null;
    choicesFailed = movies.isError;
  } else if (picker?.kind === "date") {
    choices = dateChoices(todayKst());
  } else if (picker?.kind === "grade") {
    choices = gradeChoices();
  }

  /** continueFrom: 버튼이 달린 답의 `context.id` — 보내는 문장은 그대로고 그 답의 날짜·극장만 이어받는다(3.4.22) */
  const pickMovie = (title: string, continueFrom?: string) => {
    const choice = movieFromAnswer(title, movies.data?.content);
    send(choice.text, choice.link, continueFrom);
  };

  const pick = (key: string) => {
    if (picker?.kind === "more") {
      const preset = presets.find((p) => p.presetId === key);
      if (preset) choosePreset(preset);
      return;
    }
    const choice = choices?.find((c) => c.key === key);
    if (choice) send(choice.text, choice.link);
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!blocked && !tooLong) send(draft);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    // 한글 조합 중 Enter 는 글자 확정이라 보내지 않는다
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      if (!blocked && !tooLong) send(draft);
    }
  };

  const presetChip = (preset: ChatPreset) => (
    <Chip key={preset.presetId} size="sm" disabled={blocked} title={preset.title} onClick={() => choosePreset(preset)}>
      <span className={styles.presetTitle}>{preset.title}</span>
    </Chip>
  );
  const presetChips = (
    <div className={styles.presets} role="group" aria-label="추천 질문">
      {presets.map(presetChip)}
    </div>
  );
  const presetBar = (
    <div className={styles.presets} role="group" aria-label="추천 질문">
      {presets.slice(0, BAR_PRESETS).map(presetChip)}
      {presets.length > BAR_PRESETS && (
        <Chip
          size="sm"
          disabled={blocked}
          selected={picker?.kind === "more"}
          className={styles.moreChip}
          aria-label="추천 질문 더 보기"
          title="추천 질문 더 보기"
          onClick={() => setPicker(picker?.kind === "more" ? null : { kind: "more" })}
        >
          …
        </Chip>
      )}
    </div>
  );
  const pickerOptions =
    picker?.kind === "more" ? presets.slice(BAR_PRESETS).map((p) => ({ key: p.presetId, label: p.title })) : choices;

  return (
    <section
      className={styles.panel}
      role="dialog"
      aria-modal="false"
      aria-labelledby={titleId}
      onKeyDown={(event) => {
        if (event.key !== "Escape") return;
        // 고르는 말풍선이 열려 있으면 그것만 닫는다
        if (picker) closePicker();
        else onClose();
      }}
    >
      <header className={styles.header}>
        <div className={styles.titleWrap}>
          <span className={styles.avatar} aria-hidden="true">
            <Icon name="sparkles" size={16} />
          </span>
          <div>
            <h2 id={titleId} className={styles.title}>
              AI 상영 도우미
            </h2>
            <p className={styles.subtitle}>회차·잔여석·내 예매를 물어보세요</p>
          </div>
        </div>
        <div className={styles.headerActions}>
          <Chip size="sm" selected={demo.enabled} onClick={demo.toggle} title="처리 경로·토큰·SQL 표시">
            데모 정보
          </Chip>
          <button
            type="button"
            className={styles.iconButton}
            onClick={() => {
              restart();
              setPicker(null);
            }}
            disabled={entries.length === 0 && !isPending}
            aria-label="새 대화"
            title="새 대화"
          >
            <Icon name="plus" size={17} />
          </button>
          <button type="button" className={styles.iconButton} onClick={onClose} aria-label="대화창 닫기" title="닫기">
            <Icon name="x" size={17} />
          </button>
        </div>
      </header>

      {totals && !disabled && entries.length > 0 && (
        <div className={styles.totals} role="group" aria-label="이 대화의 토큰 합계">
          <ul className={styles.totalsList}>
            <li>질문 {n(totals.questions)}</li>
            <li>Claude 답 {n(totals.claudeAnswers)}</li>
            <li>캐시 답 {n(totals.cacheAnswers)}</li>
            <li title={TOKEN_SCOPE}>
              쓴 토큰 {n(totals.usedTokens)}(입력+출력)<span className="sr-only"> — {TOKEN_SCOPE}</span>
            </li>
            <li className={styles.totalsSaved} title={TOKEN_SCOPE}>
              아낀 토큰 {n(totals.savedTokens)}(짝 질문이 실제로 쓴 양 기준)
            </li>
            {totals.unpairedCacheAnswers > 0 && <li>짝 없는 캐시 답 {n(totals.unpairedCacheAnswers)}건은 제외</li>}
          </ul>
          {/* 답이 오는 중에 기준을 옮기면 그 답만 질문 없이 세어진다 */}
          <button type="button" className={styles.totalsReset} onClick={recount} disabled={isPending}>
            여기부터 다시 세기
          </button>
        </div>
      )}

      <div className={styles.log} ref={logRef} role="log" aria-live="polite" aria-label="대화 내용">
        {notReady ? (
          <div className={styles.intro}>
            <span className={styles.introIcon} aria-hidden="true">
              <Icon name="sparkles" size={22} strokeWidth={1.5} />
            </span>
            <p className={styles.introTitle}>AI 상영 도우미는 아직 준비 중이에요</p>
            <p className={styles.introText}>곧 만나요. 예매·조회는 평소대로 이용할 수 있어요.</p>
            <Button size="sm" onClick={onClose}>
              닫기
            </Button>
          </div>
        ) : unavailable ? (
          <div className={styles.intro}>
            <span className={styles.introIcon} aria-hidden="true">
              <Icon name="alert-circle" size={22} strokeWidth={1.5} />
            </span>
            <p className={styles.introTitle}>AI 챗봇이 지금은 쉬고 있어요</p>
            <p className={styles.introText}>잠시 뒤 다시 시도해 주세요. 예매·조회는 평소대로 이용할 수 있어요.</p>
            <Button size="sm" onClick={onRetryAvailability} disabled={retryingAvailability}>
              <Icon name="rotate-cw" size={14} />
              {retryingAvailability ? "확인 중…" : "다시 시도"}
            </Button>
          </div>
        ) : entries.length === 0 ? (
          <div className={styles.intro}>
            <span className={styles.introIcon} aria-hidden="true">
              <Icon name="message-square" size={22} strokeWidth={1.5} />
            </span>
            <p className={styles.introTitle}>무엇이 궁금하세요?</p>
            <p className={styles.introText}>
              “오늘 20시 이후에 시작하는 영화”처럼 자연스럽게 물어보세요. 답은 상영 정보 기준 시각과 함께 보여 드려요.
            </p>
            {presets.length > 0 && presetChips}
          </div>
        ) : (
          entries.map((entry) => (
            <ChatEntryView
              key={entry.id}
              entry={entry}
              hold={holdOf(entry)}
              onNavigate={onClose}
              demo={notes ? { note: notes.get(entry.id), questionNo: numbers?.get(entry.id) } : null}
              onRetry={blocked ? undefined : (question, link) => send(question, link, retryContinueFrom(entry))}
              onChoose={blocked ? undefined : (message) => send(message)}
              onPickMovie={blocked ? undefined : pickMovie}
              onSeatNavigate={onClose}
              onClearContext={blocked ? undefined : (question, link) => send(question, link)}
            />
          ))
        )}
        {picker && !disabled && (
          <ChoicePicker
            prompt={PICK_PROMPTS[picker.kind]}
            options={pickerOptions}
            failed={choicesFailed}
            disabled={blocked}
            onPick={pick}
            onCancel={closePicker}
          />
        )}
        {isPending && (
          <div className={styles.pending} role="status">
            <span className={styles.dots} aria-hidden="true">
              <span />
              <span />
              <span />
            </span>
            답을 찾고 있어요
          </div>
        )}
      </div>

      {!disabled && entries.length > 0 && presets.length > 0 && (
        <div className={styles.presetBar}>{presetBar}</div>
      )}

      <form className={styles.composer} onSubmit={onSubmit}>
        <label className="sr-only" htmlFor={`${titleId}-input`}>
          질문
        </label>
        <textarea
          id={`${titleId}-input`}
          ref={inputRef}
          className={styles.input}
          rows={1}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={
            cooldownSeconds > 0 ? `${cooldownSeconds}초 뒤에 물어볼 수 있어요` : "예) 내일 건대입구 편성표 보여줘"
          }
          disabled={disabled || cooldownSeconds > 0}
          aria-invalid={tooLong || undefined}
          aria-describedby={`${titleId}-count`}
        />
        <div className={styles.composerFoot}>
          <span id={`${titleId}-count`} className={tooLong ? styles.countOver : styles.count}>
            {draft.length}/{MAX_MESSAGE_LENGTH}
          </span>
          <Button
            type="submit"
            variant="primary"
            size="sm"
            className={styles.send}
            disabled={blocked || !trimmed || tooLong}
            aria-label="보내기"
          >
            <Icon name="send" size={15} />
          </Button>
        </div>
      </form>
    </section>
  );
}
