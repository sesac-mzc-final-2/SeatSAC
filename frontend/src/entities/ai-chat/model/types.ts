/** contracts/openapi/ai.openapi.yaml (AI-01·AI-04). 필드는 항상 있고 해당 없으면 null */

export type ChatPath =
  | "CACHE_EXACT"
  | "CACHE_SEMANTIC"
  | "PRESET"
  | "NL2SQL"
  | "MY_BOOKINGS"
  | "REJECTED"
  | "OUT_OF_SCOPE"
  | "CLARIFY"
  // 채팅으로 좌석 잡기 (초안 3.4.19 — ai 플래그가 켜졌을 때만 온다)
  | "HOLD_CONFIRM"
  | "HOLD_RESULT"
  | "HOLD_GUIDE";

export type ChatColumnType = "text" | "integer" | "real";

export interface ChatColumn {
  /** SQL 별칭 (예: `movie_title`) */
  name: string;
  type: ChatColumnType;
}

export type ChatCell = string | number | null;

export interface LlmUsage {
  /** 0이면 Claude API를 부르지 않음(캐시·내 예매) */
  calls: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens?: number;
}

export interface ChatRejection {
  reason: string;
  message: string;
  object?: string | null;
}

/** `CLARIFY`(되묻기) 버튼. 누르면 `message` 를 그대로 다음 질문으로 보낸다 */
export interface ChatChoice {
  label: string;
  message: string;
}

/**
 * 채팅으로 좌석 잡기 요약 (초안 3.4.19). ai 는 URL 을 만들지 않는다 —
 * 화면이 `bookingId` 로 결제 화면, `screeningId` 로 좌석 선택 화면 링크를 만든다
 */
export interface ChatSeatHold {
  screeningId: number | null;
  /** 선점 성공이면 새 PENDING 예매, `reason: ACTIVE_HOLD_EXISTS` 면 이미 진행 중인 예매 */
  bookingId: number | null;
  seatLabels: string[];
  amount: number | null;
  /** 선점 만료 시각 `+09:00` (결제 기한) */
  expiresAt: string | null;
  /** 확인 버튼을 누를 수 있는 기한 `+09:00` (확인 요청 뒤 2분). 버튼이 달린 답에만 */
  confirmExpiresAt?: string | null;
  /** 선점하지 않았거나 못한 까닭 (성공·확인 요청은 null) */
  reason: string | null;
}

/**
 * 이 답과 이어진 캐시 항목 (3.4.20 데모). 질문 글자는 없다 — `entryId` 가 같은 두 답을 화면이 짝짓는다.
 * `stored: true` = Claude 가 답하고 캐시에 저장함(다음 질문의 짝) · `false` = 그 캐시 항목에서 나온 답
 */
export interface ChatCacheRef {
  entryId: string;
  stored: boolean;
  /** 의미 캐시(`CACHE_SEMANTIC`)일 때만 0~1, 그 밖은 null */
  similarity: number | null;
}

export interface ChatRequest {
  message: string;
  sessionId?: string;
}

export interface ChatResponse {
  sessionId: string;
  answer: string;
  path: ChatPath;
  columns: ChatColumn[] | null;
  rows: ChatCell[][] | null;
  /**
   * `rows` 와 같은 순서의 회차 ID — 그 행의 좌석 선택 화면으로 넘길 때 쓴다. 행이 회차 하나가 아니면(집계 등) null.
   * 계약(#150)에서 추가되는 필드. 그 전의 ai 응답에는 없어서 선택 필드로 둔다
   */
  screeningIds?: (number | null)[] | null;
  rowCount: number;
  truncated: boolean;
  sql: string | null;
  presetId: string | null;
  /** 스냅샷 기준 시각 `+09:00` */
  asOf: string;
  llm: LlmUsage;
  latencyMs: number;
  rejection: ChatRejection | null;
  /** `CLARIFY` 일 때만 버튼 목록 (배포 순서상 옛 ai 는 이 필드를 보내지 않을 수 있다) */
  choices?: ChatChoice[] | null;
  /** `HOLD_*` 일 때만 객체, 그 밖은 null (배포 순서상 옛 ai 는 이 필드를 보내지 않을 수 있다) */
  seatHold?: ChatSeatHold | null;
  /** 캐시에서 나왔거나 캐시에 저장한 답만. 그 밖은 null (배포 순서상 옛 ai 는 이 필드를 보내지 않을 수 있다) */
  cache?: ChatCacheRef | null;
}

export interface ChatPreset {
  presetId: string;
  /** 버튼 글자 */
  title: string;
  /** 누르면 그대로 `message` 로 보낸다 */
  example: string;
}

export interface ChatPresetList {
  items: ChatPreset[];
}
