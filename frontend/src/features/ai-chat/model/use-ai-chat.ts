import { useMutation } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import { chatErrorInfo, sendChatMessage, type ChatErrorInfo, type ChatResponse } from "@/entities/ai-chat";
import { traceIdOf } from "@/shared/api";

export const MAX_MESSAGE_LENGTH = 300;

export type ChatEntry =
  | { id: number; kind: "question"; text: string }
  | { id: number; kind: "answer"; response: ChatResponse }
  | { id: number; kind: "error"; question: string; info: ChatErrorInfo; traceId: string | null };

type NewEntry = { [K in ChatEntry["kind"]]: Omit<Extract<ChatEntry, { kind: K }>, "id"> }[ChatEntry["kind"]];

/**
 * 대화 한 묶음. `sessionId` 는 서버가 발급하고 다음 질문에 그대로 보낸다(후속 질문 문맥, 30분).
 * 대화는 메모리에만 — 새로고침·로그아웃하면 사라진다(질문에 개인정보가 들어갈 수 있어 저장하지 않는다).
 * 동시 1건: 답을 기다리는 동안은 보내지 않는다(서버도 409 `AI_QUERY_IN_PROGRESS`).
 */
export function useAiChat() {
  const [entries, setEntries] = useState<ChatEntry[]>([]);
  const [cooldownUntil, setCooldownUntil] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const sessionId = useRef<string | undefined>(undefined);
  const nextId = useRef(1);
  const controller = useRef<AbortController | null>(null);

  const mutation = useMutation({
    mutationFn: ({ message, signal }: { message: string; signal: AbortSignal }) =>
      sendChatMessage({ message, sessionId: sessionId.current }, signal),
  });
  const { mutate, reset: resetMutation, isPending } = mutation;

  // 분 한도(429) 남은 시간 표시용 1초 시계 — 막혀 있을 때만 돈다
  useEffect(() => {
    if (cooldownUntil === null) return;
    const timer = window.setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= cooldownUntil) setCooldownUntil(null);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [cooldownUntil]);

  useEffect(() => () => controller.current?.abort(), []);

  const cooldownSeconds = cooldownUntil === null ? 0 : Math.max(0, Math.ceil((cooldownUntil - now) / 1000));

  const append = useCallback((entry: NewEntry) => {
    const id = nextId.current++;
    setEntries((prev) => [...prev, { ...entry, id } as ChatEntry]);
  }, []);

  const ask = useCallback(
    (raw: string): boolean => {
      const message = raw.trim();
      if (!message || message.length > MAX_MESSAGE_LENGTH || isPending || cooldownSeconds > 0) return false;
      append({ kind: "question", text: message });
      controller.current = new AbortController();
      mutate(
        { message, signal: controller.current.signal },
        {
          onSuccess: (response) => {
            sessionId.current = response.sessionId;
            append({ kind: "answer", response });
          },
          onError: (error) => {
            if (controller.current?.signal.aborted) return;
            const info = chatErrorInfo(error);
            if (info.cooldownSeconds) {
              const until = Date.now() + info.cooldownSeconds * 1000;
              setNow(Date.now());
              setCooldownUntil(until);
            }
            append({ kind: "error", question: message, info, traceId: traceIdOf(error) });
          },
        },
      );
      return true;
    },
    [isPending, cooldownSeconds, mutate, append],
  );

  /** 새 대화: 화면 기록과 sessionId 를 비운다(서버 세션은 30분 뒤 스스로 사라진다) */
  const restart = useCallback(() => {
    controller.current?.abort();
    resetMutation();
    sessionId.current = undefined;
    setEntries([]);
  }, [resetMutation]);

  return { entries, ask, restart, isPending, cooldownSeconds };
}
