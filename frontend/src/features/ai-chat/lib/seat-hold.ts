import type { ChatResponse } from "@/entities/ai-chat";

/**
 * 채팅으로 좌석 잡기 (초안 3.4.19)의 확인 단계 — 화면 쪽 안전장치.
 * 서버는 확인 ID가 맞는 대기 행동이 있을 때만 잡는다. 화면은 그 전에, 지난 확인 요청의 버튼·
 * 그 버튼을 눌렀다 실패한 항목의 「다시 묻기」가 눌리지 않게 막는다.
 */

/** 확인·취소 버튼이 보내는 문장 (`좌석 잡기 #c7f2a1` · `취소 #c7f2a1`) */
const HOLD_REPLY = /^(좌석 잡기|취소)\s*#[0-9a-f]{6}$/;
const CONFIRM_REPLY = /^좌석 잡기\s*#[0-9a-f]{6}$/;

/**
 * 확인·취소 버튼의 문장 모양인지. **버튼 모양(주/보조)을 고를 때**와, 어느 답의 버튼인지 알 수 없는
 * 오류 항목을 막을 때만 쓴다 — 버튼을 막을지는 문장이 아니라 응답 값으로 정한다(`hasHoldButtons`)
 */
export const isHoldReply = (message: string): boolean => HOLD_REPLY.test(message.trim());
/** 확인(주 버튼)인지 — 취소(보조)와 모양을 구분한다 */
export const isHoldConfirm = (message: string): boolean => CONFIRM_REPLY.test(message.trim());

/**
 * 기한이 있는 좌석 잡기 버튼이 달린 답인지 — 확인 요청, 회차 고르기, 「보내지 못했다」 뒤 버튼이 다시 온 답.
 * 버튼 문장의 글자 형식이 아니라 응답의 값으로 정한다(서버가 문장 형식을 바꿔도 안전장치가 풀리지 않게)
 */
export const hasHoldButtons = (response: ChatResponse): boolean =>
  response.path === "HOLD_CONFIRM" || !!response.seatHold?.confirmExpiresAt;

export interface ActiveHold {
  /** 그 답 항목의 id */
  entryId: number;
  /** 지금 눌러도 되는 버튼의 문장 (확인·취소, 또는 회차) */
  messages: string[];
  /** 버튼을 누를 수 있는 기한 (ms) */
  expiresAt: number;
}

type EntryLike = { id: number; kind: string; response?: ChatResponse; question?: string };

/**
 * 지금 눌러도 되는 좌석 잡기 버튼: **가장 최근 답**이 그런 버튼이 달린 답일 때만.
 * 그 뒤에 다른 답이 하나라도 왔으면(새 확인 요청 포함) 앞의 것은 지난 것이다.
 * 기한을 읽을 수 없으면(값이 없거나 모양이 다르면) 없는 것으로 본다 — 버튼을 막는다
 */
export function activeHold(entries: readonly EntryLike[]): ActiveHold | null {
  const last = [...entries].reverse().find((entry) => entry.kind === "answer" && entry.response);
  const response = last?.response;
  if (!last || !response || !hasHoldButtons(response)) return null;
  const expiresAt = Date.parse(response.seatHold?.confirmExpiresAt ?? "");
  if (!Number.isFinite(expiresAt)) return null;
  return {
    entryId: last.id,
    messages: (response.choices ?? []).map((choice) => choice.message),
    expiresAt,
  };
}

/** 대화에 나온 좌석 잡기 버튼의 문장 전부 (지난 답의 것 포함) — 오류 항목이 그 버튼을 누른 것인지 볼 때 쓴다 */
export function holdMessages(entries: readonly EntryLike[]): Set<string> {
  const found = new Set<string>();
  for (const entry of entries) {
    if (entry.kind !== "answer" || !entry.response || !hasHoldButtons(entry.response)) continue;
    for (const choice of entry.response.choices ?? []) found.add(choice.message);
  }
  return found;
}

/** `active` 지금 눌러도 된다 · `expired` 유효한 요청인데 기한이 지났다 · `stale` 지난 요청의 것 */
export type HoldState = "active" | "expired" | "stale";

/**
 * 이 항목의 좌석 잡기 버튼(답)이나 「다시 묻기」(그 버튼을 눌렀다 실패한 오류 항목)를 눌러도 되는지.
 * 좌석 잡기와 상관없는 항목이면 undefined
 */
export function holdStateOf(
  entry: EntryLike,
  pending: ActiveHold | null,
  known: ReadonlySet<string>,
  now: number,
): HoldState | undefined {
  let mine: boolean;
  if (entry.kind === "answer" && entry.response) {
    if (!hasHoldButtons(entry.response)) return undefined;
    mine = pending?.entryId === entry.id;
  } else if (entry.kind === "error" && entry.question !== undefined) {
    if (!known.has(entry.question) && !isHoldReply(entry.question)) return undefined;
    mine = pending?.messages.includes(entry.question) ?? false;
  } else {
    return undefined;
  }
  if (!pending || !mine) return "stale";
  return pending.expiresAt <= now ? "expired" : "active";
}
