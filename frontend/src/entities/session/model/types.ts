export interface SessionUser {
  /** Cognito sub (= X-User-Id) */
  id: string;
  name: string | null;
  email: string | null;
}

/**
 * - loading: 콜백 처리·조용한 갱신 중
 * - anonymous: 로그인 안 됨
 * - authenticated: Access Token 있음
 * - unavailable: Cognito 설정이 없어 로그인 기능을 쓸 수 없음 (로컬 기본)
 *
 * 역할(USER·ADMIN)은 토큰을 파싱하지 않고 `GET /users/me` 응답(`role`)에서 받는다 (3.3.6, W8·W9).
 */
export type SessionStatus = "loading" | "anonymous" | "authenticated" | "unavailable";

export interface Session {
  status: SessionStatus;
  user: SessionUser | null;
  /** 콜백 처리 실패 등 */
  error: Error | null;
  /** 로그인 전에 저장해 둔 돌아갈 경로 (Hosted UI state) */
  returnTo: string | null;
  /** Hosted UI로 이동. returnTo는 콜백 뒤 돌아갈 경로 (W3 → W7 → W4) */
  signIn: (returnTo?: string) => void;
  signOut: () => void;
}
