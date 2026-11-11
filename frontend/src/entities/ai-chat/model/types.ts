/** contracts/openapi/ai.openapi.yaml (AI-01·AI-04). 필드는 항상 있고 해당 없으면 null */

export type ChatPath = "CACHE_EXACT" | "CACHE_SEMANTIC" | "PRESET" | "NL2SQL" | "MY_BOOKINGS" | "REJECTED" | "OUT_OF_SCOPE";

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
  rowCount: number;
  truncated: boolean;
  sql: string | null;
  presetId: string | null;
  /** 스냅샷 기준 시각 `+09:00` */
  asOf: string;
  llm: LlmUsage;
  latencyMs: number;
  rejection: ChatRejection | null;
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
