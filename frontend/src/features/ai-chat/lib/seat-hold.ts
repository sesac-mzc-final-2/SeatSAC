import type { ChatResponse } from "@/entities/ai-chat";

/**
 * 채팅으로 좌석 잡기 (초안 3.4.19)의 확인 단계 — 화면 쪽 안전장치.
 * 서버는 확인 ID가 맞는 대기 행동이 있을 때만 잡는다. 화면은 그 전에, 지난 확인 요청의 버튼·
 * 그 버튼을 눌렀다 실패한 항목의 「다시 묻기」가 눌리지 않게 막는다.
 */

/** 확인·취소 버튼이 보내는 문장 (`좌석 잡기 #c7f2a1` · `취소 #c7f2a1`) */
const HOLD_REPLY = /^(좌석 잡기|취소)\s*#[0-9a-f]{6}$/;
const CONFIRM_REPLY = /^좌석 잡기\s*#[0-9a-f]{6}$/;

export const isHoldReply = (message: string): boolean => HOLD_REPLY.test(message.trim());
/** 확인(주 버튼)인지 — 취소(보조)와 모양을 구분한다 */
export const isHoldConfirm = (message: string): boolean => CONFIRM_REPLY.test(message.trim());

/** 확인·취소 버튼이 달린 답인지 (확인 요청, 또는 「보내지 못했다」 뒤 버튼이 다시 온 답) */
export const hasHoldButtons = (response: ChatResponse): boolean =>
  !!response.seatHold?.confirmExpiresAt && (response.choices ?? []).some((choice) => isHoldReply(choice.message));

export interface ActiveHold {
  /** 그 답 항목의 id */
  entryId: number;
  /** 지금 눌러도 되는 확인·취소 문장 */
  messages: string[];
  /** 확인 기한 (ms) */
  expiresAt: number;
}

type EntryLike = { id: number; kind: string; response?: ChatResponse };

/**
 * 지금 눌러도 되는 확인 요청: **가장 최근 답**이 확인·취소 버튼이 달린 답일 때만.
 * 그 뒤에 다른 답이 하나라도 왔으면(새 확인 요청 포함) 앞의 것은 지난 것이다
 */
export function activeHold(entries: readonly EntryLike[]): ActiveHold | null {
  const last = [...entries].reverse().find((entry) => entry.kind === "answer" && entry.response);
  const response = last?.response;
  if (!last || !response || !hasHoldButtons(response)) return null;
  const expiresAt = Date.parse(response.seatHold?.confirmExpiresAt ?? "");
  if (!Number.isFinite(expiresAt)) return null;
  return {
    entryId: last.id,
    messages: (response.choices ?? []).map((choice) => choice.message).filter(isHoldReply),
    expiresAt,
  };
}
