import type { ChatResponse } from "@/entities/ai-chat";

/**
 * 문맥 이어받기 (3.4.22). 답 아래 영화 버튼이 예전에는 `{제목} 어디서 몇 시에 해?` 만 보내 앞 답의 조건이 사라졌다
 * ("부산본점 내일 영화 뭐해?" → [영화] → 전 극장·전 날짜).
 * 이제 그 답의 `context.id` 를 `continueFrom` 으로 같이 보낸다 — 보내는 문장은 그대로고, 조건은 ai 가 값으로 들고 있다.
 * 위로 올려 예전 답의 버튼을 눌러도 그 답의 조건을 쓴다(직전 답이 아니라 버튼이 달린 답의 id).
 */

type WithContext = Pick<ChatResponse, "context">;

/**
 * 이 답의 영화 버튼이 보낼 `continueFrom`. 이어받을 조건(날짜 또는 극장)이 있는 답에만 —
 * 조건이 없는 답·영화만 있는 답·옛 ai(필드 없음)면 undefined → 예전처럼 독립 질문.
 * 시각(`time` — "20시 이후")은 보지 않는다: 시각은 날짜가 있어야만 오는 값이라 날짜 판단으로 충분하다.
 * 이어받을 것이 없는데 보내면 서버가 평소 경로로 답하고 화면은 「이어받지 못해…」를 잘못 띄운다
 */
export function continueFromOf(response: WithContext): string | undefined {
  const context = response.context;
  if (!context || !context.id || !(context.date || context.theater)) return undefined;
  return context.id;
}

/** 앞 답의 조건을 이어받은 답이면 그 조건 글자(`내일 11/20(금) · 부산본점`, `오늘 11/19(목) · 20시 이후`), 아니면 null. 글자는 서버 `label` 그대로다 */
export function inheritedLabel(response: WithContext): string | null {
  const context = response.context;
  if (!context || !Array.isArray(context.inherited) || context.inherited.length === 0) return null;
  return context.label || null;
}

/**
 * `continueFrom` 을 보냈는데 이어받지 못한 답 — 전체 기준으로 찾았다고 알린다.
 * 세션 만료(30분)·답 5개가 넘게 지난 버튼·지난 날짜, 또는 서버가 평소 경로로 돌린 경우(틀이 담지 못하는 문장 등)다.
 * 결과 표가 있는 답에만: 범위 밖·거부 답에는 「전체 기준으로 찾았어요」가 맞지 않는다
 */
export const isContextExpired = (
  response: WithContext & Pick<ChatResponse, "columns" | "rows">,
  continueFrom: string | undefined,
): boolean => !!continueFrom && inheritedLabel(response) === null && !!response.columns && !!response.rows;

export const CONTEXT_EXPIRED_NOTICE = "이전 조건을 이어받지 못해 전체 기준으로 찾았어요";
