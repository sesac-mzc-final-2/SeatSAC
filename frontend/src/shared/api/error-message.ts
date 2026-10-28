import { ApiError, NetworkError } from "./http";

/** 공통 오류 코드 → 사용자 문구 (docs/conventions.md 6장). 화면별 문구는 overrides로 덮어쓴다. */
const MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: "요청 값이 올바르지 않습니다.",
  UNAUTHORIZED: "로그인이 필요합니다.",
  FORBIDDEN: "이 기능을 사용할 권한이 없습니다.",
  NOT_FOUND: "요청한 정보를 찾을 수 없습니다.",
  SERVICE_UNAVAILABLE: "서비스가 잠시 응답하지 않습니다. 다시 시도해 주세요.",
  INTERNAL_ERROR: "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
};

const FALLBACK = "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

export function messageForError(error: unknown, overrides?: Record<string, string>): string {
  if (error instanceof ApiError) return overrides?.[error.code] ?? MESSAGES[error.code] ?? FALLBACK;
  if (error instanceof NetworkError) return "네트워크에 연결할 수 없습니다. 연결 상태를 확인해 주세요.";
  return FALLBACK;
}

export function traceIdOf(error: unknown): string | null {
  return error instanceof ApiError ? error.traceId : null;
}

export function isApiError(error: unknown, status?: number, code?: string): error is ApiError {
  if (!(error instanceof ApiError)) return false;
  if (status !== undefined && error.status !== status) return false;
  if (code !== undefined && error.code !== code) return false;
  return true;
}
