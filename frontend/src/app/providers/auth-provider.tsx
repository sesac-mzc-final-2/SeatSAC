import { InMemoryWebStorage, WebStorageStateStore, type User } from "oidc-client-ts";
import { useEffect, useMemo, type ReactNode } from "react";
import { AuthProvider as OidcProvider, useAuth, type AuthProviderProps } from "react-oidc-context";
import {
  SessionContext,
  roleFromAccessToken,
  unavailableSession,
  type Session,
  type SessionStatus,
  type SessionUser,
} from "@/entities/session";
import { configureHttpAuth } from "@/shared/api";
import { env, isCognitoConfigured } from "@/shared/config";

/**
 * Cognito Hosted UI (code + PKCE, ADR-37). 토큰은 메모리에만 둔다 (InMemoryWebStorage, ADR-63) —
 * 새로고침하면 Cognito 세션으로 다시 받는다. PKCE state(sessionStorage 기본값)는 토큰이 아니다.
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

interface SigninState {
  returnTo?: unknown;
}

/** Hosted UI state로 넘긴 돌아갈 경로. 같은 출처의 절대 경로만 허용한다 (열린 리다이렉트 방지) */
function readReturnTo(state: unknown): string | null {
  const returnTo = (state as SigninState | undefined)?.returnTo;
  if (typeof returnTo !== "string") return null;
  return returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : null;
}

function toSessionUser(user: User): SessionUser {
  const profile = user.profile;
  const username = profile["cognito:username"];
  return {
    id: profile.sub,
    name: profile.name ?? (typeof username === "string" ? username : null),
    email: profile.email ?? null,
    role: roleFromAccessToken(user.access_token),
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

  useEffect(() => {
    configureHttpAuth({
      getAccessToken: () => auth.user?.access_token ?? null,
      onUnauthorized: async () => {
        const renewed = await auth.signinSilent();
        return renewed?.access_token ?? null;
      },
    });
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
