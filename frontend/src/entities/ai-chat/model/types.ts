/** contracts/openapi/ai.openapi.yaml (AI-01·AI-04). 필드는 항상 있고 해당 없으면 null */

export type ChatPath =
  | "CACHE_EXACT"
  | "CACHE_SEMANTIC"
  | "PRESET"
  | "NL2SQL"
  | "MY_BOOKINGS"
  | "REJECTED"
  | "OUT_OF_SCOPE"
  | "CLARIFY";

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
   * 계약에서는 항상 있는 필드지만, 이 필드가 생기기 전 버전의 ai 가 답하면 없을 수 있어 선택으로 둔다
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
