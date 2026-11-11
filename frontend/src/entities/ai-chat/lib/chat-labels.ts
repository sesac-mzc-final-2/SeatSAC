import { timeOfIso } from "@/shared/lib";
import type { ChatPath, ChatResponse } from "../model/types";

const PATH_LABELS: Record<ChatPath, string> = {
  CACHE_EXACT: "정확 일치 캐시",
  CACHE_SEMANTIC: "의미 캐시",
  PRESET: "프리셋",
  NL2SQL: "NL2SQL",
  MY_BOOKINGS: "내 예매",
  REJECTED: "SQL 거부",
  OUT_OF_SCOPE: "범위 밖",
};

/** 데모 모드의 처리 경로 표시 */
export const chatPathLabel = (path: ChatPath): string => PATH_LABELS[path] ?? path;

/** Claude 를 부르지 않은 답 (캐시 적중·내 예매) — 토큰 절감 데모에서 강조한다 */
export const isTokenFree = (response: Pick<ChatResponse, "llm">): boolean => response.llm.calls === 0;

/** `asOf`(+09:00) → `19:42 기준` */
export const asOfLabel = (asOf: string): string => `${timeOfIso(asOf)} 기준`;

/** 결과 표가 있는 답인지 (`REJECTED`·`OUT_OF_SCOPE` 는 columns·rows 가 null) */
export const hasResultTable = (response: Pick<ChatResponse, "columns" | "rows">): boolean =>
  !!response.columns && response.columns.length > 0 && !!response.rows;
