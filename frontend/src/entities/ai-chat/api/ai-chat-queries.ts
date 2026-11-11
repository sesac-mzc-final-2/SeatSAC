import { queryOptions } from "@tanstack/react-query";
import { isApiError } from "@/shared/api";
import { listChatPresets } from "./ai-chat-api";

export const aiChatKeys = {
  all: ["ai"] as const,
  presets: () => [...aiChatKeys.all, "presets"] as const,
};

/**
 * 추천 질문은 정적 목록이라 오래 둔다. 404(플래그 off)는 다시 시도하지 않는다 — 화면이 챗봇을 숨긴다.
 * 503(ai 파드 없음, 평소 `ng-ai` 0대)도 자동 재시도하지 않고 패널을 열 때 사용자가 다시 시도한다
 */
export const chatPresetsQuery = () =>
  queryOptions({
    queryKey: aiChatKeys.presets(),
    queryFn: ({ signal }) => listChatPresets(signal),
    staleTime: 30 * 60 * 1000,
    retry: (failureCount, error) => !isApiError(error) && failureCount < 1,
    refetchOnWindowFocus: false,
  });
