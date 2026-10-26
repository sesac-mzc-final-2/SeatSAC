/** X-User-Role 값 (docs/conventions.md 6장). Cognito 그룹 ADMIN → ADMIN, 그 외 USER */
export type UserRole = "USER" | "ADMIN";

export interface SessionUser {
  /** Cognito sub (= X-User-Id) */
  id: string;
  name: string | null;
  email: string | null;
  role: UserRole;
}

/**
 * - loading: 콜백 처리·조용한 갱신 중
 * - anonymous: 로그인 안 됨
 * - authenticated: Access Token 있음
 * - unavailable: Cognito 설정이 없어 로그인 기능을 쓸 수 없음 (로컬 기본)
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
