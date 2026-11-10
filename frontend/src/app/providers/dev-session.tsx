import { useMemo, useState, type ReactNode } from "react";
import { SessionContext, type Session } from "@/entities/session";
import { configureHttpAuth } from "@/shared/api";

/**
 * 개발 서버 전용 가짜 로그인 (`DEV_SESSION=1 npm run dev:mock`, `isDevSessionEnabled`). Cognito 없이 마이페이지·예매 화면을
 * 보기 위한 것으로, 토큰은 목 서버가 "있는지"만 보는 자리 표시 값이다. 빌드에서는 플래그가 항상 꺼져 있어 배포본에서 켜질 길이 없다.
 */
const DEV_ACCESS_TOKEN = "dev-session";

export function DevSessionProvider({ children }: { children: ReactNode }) {
  // 토큰 공급자는 자식이 첫 요청(GET /users/me)을 보내기 전에 꽂혀 있어야 한다(401 은 재시도하지 않는다).
  // effect 는 자식의 쿼리 구독보다 늦을 수 있어 첫 렌더의 state 초기화에서 한 번 꽂는다 (멱등)
  const [httpAuthReady] = useState(() => {
    configureHttpAuth({ getAccessToken: () => DEV_ACCESS_TOKEN, onUnauthorized: null, onSessionExpired: null });
    return true;
  });

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

  return <SessionContext.Provider value={session}>{httpAuthReady ? children : null}</SessionContext.Provider>;
}
