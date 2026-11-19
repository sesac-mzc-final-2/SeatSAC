import type { ChatResponse } from "@/entities/ai-chat";

/**
 * 문맥 이어받기 (3.4.22). 답 아래 영화 버튼이 예전에는 `{제목} 어디서 몇 시에 해?` 만 보내 앞 답의 조건이 사라졌다
 * ("부산본점 내일 영화 뭐해?" → [영화] → 전 극장·전 날짜).
 * 이제 그 답의 `context.id` 를 `continueFrom` 으로 같이 보낸다 — 보내는 문장은 그대로고, 조건은 ai 가 값으로 들고 있다.
 * 위로 올려 예전 답의 버튼을 눌러도 그 답의 조건을 쓴다(직전 답이 아니라 버튼이 달린 답의 id).
 */

type WithContext = Pick<ChatResponse, "context">;

/** 이 답의 영화 버튼이 보낼 `continueFrom`. 조건이 없는 답·옛 ai(필드 없음)면 undefined → 예전처럼 독립 질문 */
export const continueFromOf = (response: WithContext): string | undefined => response.context?.id || undefined;

/** 앞 답의 조건을 이어받은 답이면 그 조건 글자(`내일 11/20(금) · 부산본점`), 아니면 null */
export function inheritedLabel(response: WithContext): string | null {
  const context = response.context;
  if (!context || !Array.isArray(context.inherited) || context.inherited.length === 0) return null;
  return context.label || null;
}

/**
 * `continueFrom` 을 보냈는데 이어받지 못한 답 (세션 30분 만료 · 지난 날짜 등) — 전체 기준으로 찾았다고 알린다.
 * 결과 표가 있는 답에만: 범위 밖·거부 답에는 「전체 기준으로 찾았어요」가 맞지 않는다
 */
export const isContextExpired = (
  response: WithContext & Pick<ChatResponse, "columns" | "rows">,
  continueFrom: string | undefined,
): boolean => !!continueFrom && inheritedLabel(response) === null && !!response.columns && !!response.rows;

export const CONTEXT_EXPIRED_NOTICE = "이전 조건이 만료되어 전체 기준으로 찾았어요";
