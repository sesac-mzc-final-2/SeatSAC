import { InMemoryWebStorage, WebStorageStateStore, type User } from "oidc-client-ts";
import { useEffect, useMemo, type ReactNode } from "react";
import { AuthProvider as OidcProvider, hasAuthParams, useAuth, type AuthProviderProps } from "react-oidc-context";
import {
  SessionContext,
  unavailableSession,
  type Session,
  type SessionStatus,
  type SessionUser,
} from "@/entities/session";
import { configureHttpAuth } from "@/shared/api";
import { env, isCognitoConfigured } from "@/shared/config";

/**
 * Cognito Hosted UI (code + PKCE, ADR-37). 토큰은 메모리에만 둔다 (InMemoryWebStorage, ADR-63) —
 * 새로고침하면 Cognito 세션 쿠키로 다시 받는다. PKCE state(sessionStorage 기본값)는 토큰이 아니다.
 */
const oidcConfig: AuthProviderProps = {
  authority: env.cognito.authority,
  client_id: env.cognito.clientId,
  redirect_uri: env.cognito.redirectUri,
  response_type: "code",
  scope: "openid email profile",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  // 콜백 처리 뒤 ?code=&state= 를 주소에서 지운다 (새로고침 때 재처리 방지). 이동은 /callback 화면이 한다
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};

/**
 * "이 브라우저에서 로그인한 적 있음" 표시. 토큰이 아니라 새로고침 뒤 Cognito 세션으로 자동 재발급(signinRedirect)을
 * 시도할지 정하는 플래그다. 없으면 비로그인 방문자를 Hosted UI 로 보내지 않는다.
 */
const SIGNED_IN_FLAG = "seatsac.signedIn";
const signedInFlag = {
  get: (): boolean => {
    try {
      return window.localStorage.getItem(SIGNED_IN_FLAG) === "1";
    } catch {
      return false;
    }
  },
  set: (value: boolean): void => {
    try {
      if (value) window.localStorage.setItem(SIGNED_IN_FLAG, "1");
      else window.localStorage.removeItem(SIGNED_IN_FLAG);
    } catch {
      // 저장소를 못 쓰면 자동 복구만 포기한다
    }
  },
};

interface SigninState {
  returnTo?: unknown;
}

/** Hosted UI state로 넘긴 돌아갈 경로. 같은 출처의 절대 경로만 허용한다 (열린 리다이렉트 방지) */
function readReturnTo(state: unknown): string | null {
  const returnTo = (state as SigninState | undefined)?.returnTo;
  if (typeof returnTo !== "string") return null;
  return returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : null;
}

const currentPath = (): string => `${window.location.pathname}${window.location.search}`;

function toSessionUser(user: User): SessionUser {
  const profile = user.profile;
  const username = profile["cognito:username"];
  return {
    id: profile.sub,
    name: profile.name ?? (typeof username === "string" ? username : null),
    email: profile.email ?? null,
  };
}

function cognitoLogoutUrl(): string | null {
  if (!env.cognito.domain) return null;
  const params = new URLSearchParams({
    client_id: env.cognito.clientId,
    logout_uri: `${window.location.origin}/`,
  });
  return `${env.cognito.domain}/logout?${params.toString()}`;
}

/** react-oidc-context 상태 → entities/session의 Session */
function SessionBridge({ children }: { children: ReactNode }) {
  const auth = useAuth();

  // 401 처리: 1회 갱신 → 그래도 401 이면 세션 만료로 보고 로그인 흐름 시작 (2.2.5 공통 규칙)
  useEffect(() => {
    configureHttpAuth({
      getAccessToken: () => auth.user?.access_token ?? null,
      onUnauthorized: async () => {
        const renewed = await auth.signinSilent().catch(() => null);
        return renewed?.access_token ?? null;
      },
      onSessionExpired: () => {
        void auth.removeUser().then(() => auth.signinRedirect({ state: { returnTo: currentPath() } }));
      },
    });
  }, [auth]);

  // 로그인 상태 플래그 유지 + 새로고침 복구: 전에 로그인했던 브라우저라면 초기화가 끝난 익명 상태에서 Cognito 세션으로 다시 받는다
  useEffect(() => {
    if (auth.isLoading || auth.activeNavigator || hasAuthParams()) return;
    if (auth.isAuthenticated) {
      signedInFlag.set(true);
      return;
    }
    if (auth.error) {
      // 복구 시도가 실패하면(로그인 취소·Cognito 세션 만료) 플래그를 지워 무한 재시도를 막는다
      signedInFlag.set(false);
      return;
    }
    if (signedInFlag.get()) {
      signedInFlag.set(false);
      void auth.signinRedirect({ state: { returnTo: currentPath() } });
    }
  }, [auth]);

  const session = useMemo<Session>(() => {
    const status: SessionStatus = auth.isLoading ? "loading" : auth.isAuthenticated ? "authenticated" : "anonymous";
    return {
      status,
      user: auth.user && status === "authenticated" ? toSessionUser(auth.user) : null,
      error: auth.error ?? null,
      returnTo: readReturnTo(auth.user?.state),
      signIn: (returnTo) => {
        void auth.signinRedirect({ state: { returnTo: returnTo ?? "/" } });
      },
      signOut: () => {
        signedInFlag.set(false);
        void auth.removeUser().then(() => {
          const logoutUrl = cognitoLogoutUrl();
          if (logoutUrl) window.location.assign(logoutUrl);
        });
      },
    };
  }, [auth]);

  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}

/** Cognito 값이 없으면(로컬 기본) 로그인 기능을 끈 세션으로 조회 화면만 동작한다 */
export function AuthProvider({ children }: { children: ReactNode }) {
  if (!isCognitoConfigured) {
    return <SessionContext.Provider value={unavailableSession}>{children}</SessionContext.Provider>;
  }
  return (
    <OidcProvider {...oidcConfig}>
      <SessionBridge>{children}</SessionBridge>
    </OidcProvider>
  );
}
