import { env } from "@/shared/config";

/** 로그아웃 때 refresh token 폐기 요청을 기다리는 최대 시간 */
export const REVOKE_TIMEOUT_MS = 5000;

/**
 * Cognito `/oauth2/revoke` 로 refresh token 을 폐기한다 (CWE-613: 복사된 토큰이 만료 전까지 살아 있지 않게).
 *
 * oidc-client-ts 의 `revokeTokens()` 를 쓰지 않는 이유: 끝나면 토큰을 뺀 사용자를 다시 저장하고 `UserLoaded` 를 올려서,
 * 제한 시간 뒤 늦게 끝나면 `removeUser()` 로 지운 로그인 상태가 되살아난다. 여기서는 토큰 문자열만 받아 직접 부르고,
 * 제한 시간이 지나면 요청 자체를 중단하므로 늦은 응답이 상태를 건드릴 길이 없다.
 * 실패·타임아웃은 삼킨다 — 폐기가 안 돼도 로그아웃은 계속 진행한다.
 */
export async function revokeRefreshToken(refreshToken: string | null | undefined): Promise<void> {
  if (!refreshToken || !env.cognito.domain) return;
  try {
    await fetch(`${env.cognito.domain}/oauth2/revoke`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ token: refreshToken, client_id: env.cognito.clientId }),
      signal: AbortSignal.timeout(REVOKE_TIMEOUT_MS),
    });
  } catch {
    // 네트워크 오류·타임아웃: 로그아웃을 막지 않는다
  }
}
