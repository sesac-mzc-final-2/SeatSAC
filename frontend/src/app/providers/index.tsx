import type { ReactNode } from "react";
import { ToastProvider } from "@/shared/ui";
import { AuthProvider } from "./auth-provider";
import { QueryProvider } from "./query-provider";

/** 토스트 → 쿼리(503 토스트를 쓴다) → 인증 순서 */
export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <QueryProvider>
        <AuthProvider>{children}</AuthProvider>
      </QueryProvider>
    </ToastProvider>
  );
}
