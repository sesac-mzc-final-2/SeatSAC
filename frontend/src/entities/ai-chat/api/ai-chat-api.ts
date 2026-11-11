import { http } from "@/shared/api";
import type { ChatPresetList, ChatRequest, ChatResponse } from "../model/types";

/**
 * AI-04 추천 질문 목록. 로그인 뒤 가장 먼저 불러 챗봇을 보일지 정한다:
 * 404(gateway 플래그 `APP_AI_ENABLED=false`) → 숨김, 503(ai 파드 없음) → 비활성 안내
 */
export const listChatPresets = (signal?: AbortSignal) => http<ChatPresetList>("/ai/presets", { signal });

/**
 * AI-01 질문. 조회 전용이라 `Idempotency-Key` 없음. `REJECTED`·`OUT_OF_SCOPE` 도 200.
 * 409 `AI_QUERY_IN_PROGRESS` · 429 `AI_QUOTA_EXCEEDED`(Retry-After) · 503 `AI_LLM_UNAVAILABLE`·`AI_SNAPSHOT_NOT_READY`·`SERVICE_UNAVAILABLE`
 */
export const sendChatMessage = (body: ChatRequest, signal?: AbortSignal) =>
  http<ChatResponse>("/ai/chat", { method: "POST", body, signal });
