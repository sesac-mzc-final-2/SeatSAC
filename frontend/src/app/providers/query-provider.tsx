import { QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { ApiError, isApiError, messageForError } from "@/shared/api";
import { useToast } from "@/shared/ui";

/**
 * - 4xx는 재시도하지 않는다(404·400은 바로 화면 분기). 5xx·네트워크 오류만 1회.
 * - 503 SERVICE_UNAVAILABLE은 공통 토스트 (2.2.5 공통 규칙). 화면의 ErrorState와 함께 뜬다.
 *   단 `meta.silentError`인 쿼리는 띄우지 않는다 — 실패하면 조용히 숨기는 부가 요소(쿠폰 배너 등)가 주기 재조회마다 토스트를 내지 않게
 */
export function QueryProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
  const [client] = useState(
    () =>
      new QueryClient({
        queryCache: new QueryCache({
          onError: (error, query) => {
            if (query.meta?.silentError) return;
            if (isApiError(error, 503)) toast.show(messageForError(error), { tone: "error" });
          },
        }),
        defaultOptions: {
          queries: {
            staleTime: 30 * 1000,
            refetchOnWindowFocus: false,
            retry: (failureCount, error) =>
              failureCount < 1 && !(error instanceof ApiError && error.status < 500),
          },
        },
      }),
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
