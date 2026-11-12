import { useEffect, useId, useRef, type FormEvent, type KeyboardEvent } from "react";
import type { ChatPreset } from "@/entities/ai-chat";
import { Button, Chip, Icon } from "@/shared/ui";
import { MAX_MESSAGE_LENGTH, useAiChat } from "../model/use-ai-chat";
import { useDemoMode } from "../model/use-demo-mode";
import { ChatEntryView } from "./ChatEntryView";
import styles from "./ChatPanel.module.css";

interface Props {
  /** 대화 상태는 Dock 이 들고 있다 — 창을 닫았다 열어도 이어진다 */
  chat: ReturnType<typeof useAiChat>;
  /** 작성 중인 질문도 Dock 이 들고 있다 — Escape·닫기로 창을 닫아도 남는다 */
  draft: string;
  onDraftChange: (draft: string) => void;
  presets: ChatPreset[];
  /** 추천 질문 API 가 404(gateway 플래그 off) — 「준비 중」 안내만, 닫으면 Dock 이 버튼을 숨긴다 */
  notReady: boolean;
  /** 추천 질문 API 가 503 등으로 실패 — 챗봇을 비활성 안내로 */
  unavailable: boolean;
  onRetryAvailability: () => void;
  retryingAvailability: boolean;
  onClose: () => void;
}

/** 우하단 대화창 본문 (AI-01·AI-04). 열려 있는 동안만 마운트된다 */
export function ChatPanel({
  chat,
  draft,
  onDraftChange: setDraft,
  presets,
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
  const { entries, ask, restart, isPending, cooldownSeconds } = chat;

  const disabled = notReady || unavailable;
  const blocked = disabled || isPending || cooldownSeconds > 0;
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

  const send = (text: string) => {
    if (ask(text)) {
      setDraft("");
      inputRef.current?.focus();
    }
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (!tooLong) send(draft);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    // 한글 조합 중 Enter 는 글자 확정이라 보내지 않는다
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      if (!tooLong) send(draft);
    }
  };

  const presetChips = (
    <div className={styles.presets} role="group" aria-label="추천 질문">
      {presets.map((preset) => (
        <Chip
          key={preset.presetId}
          size="sm"
          disabled={blocked}
          title={preset.example}
          onClick={() => send(preset.example)}
        >
          {preset.title}
        </Chip>
      ))}
    </div>
  );

  return (
    <section
      className={styles.panel}
      role="dialog"
      aria-modal="false"
      aria-labelledby={titleId}
      onKeyDown={(event) => {
        if (event.key === "Escape") onClose();
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
            onClick={restart}
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
              demo={demo.enabled}
              onRetry={blocked ? undefined : (question) => send(question)}
            />
          ))
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
        <div className={styles.presetBar}>{presetChips}</div>
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
