import { useEffect, useMemo, type ReactNode } from "react";
import { SessionContext, type Session } from "@/entities/session";
import { configureHttpAuth } from "@/shared/api";

/**
 * 개발 서버 전용 가짜 로그인 (`DEV_SESSION=1 npm run dev:mock`, `isDevSessionEnabled`). Cognito 없이 마이페이지·예매 화면을
 * 보기 위한 것으로, 토큰은 목 서버가 "있는지"만 보는 자리 표시 값이다. 빌드에서는 플래그가 항상 꺼져 있어 배포본에서 켜질 길이 없다.
 */
const DEV_ACCESS_TOKEN = "dev-session";

export function DevSessionProvider({ children }: { children: ReactNode }) {
  useEffect(() => {
    configureHttpAuth({ getAccessToken: () => DEV_ACCESS_TOKEN, onUnauthorized: null, onSessionExpired: null });
  }, []);

  const session = useMemo<Session>(
    () => ({
      status: "authenticated",
      user: { id: "00000000-0000-4000-8000-000000000001", name: "개발 세션", email: "dev@localhost" },
      error: null,
      returnTo: null,
      signIn: () => {},
      // 로그아웃은 새로고침으로 — 플래그가 켜져 있는 한 다시 로그인 상태가 된다
      signOut: () => window.location.assign("/"),
    }),
    [],
  );

  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}
