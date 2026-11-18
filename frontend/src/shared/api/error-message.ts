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

/**
 * gateway 503 `SERVICE_UNAVAILABLE`의 `details[{field: "service", value: 라우트 ID}]`로 멈춘 서비스를 알려 준다.
 * 한 서비스가 멈춰도 나머지는 계속 쓸 수 있다는 걸 화면에서 보여 주기 위해서다(5.1.5 장애 격리).
 * 라우트 ID는 `booking-seats`·`booking-seat-stream`처럼 서비스 이름으로 시작한다(gateway application.yml).
 */
const UNAVAILABLE_BY_SERVICE: Record<string, string> = {
  catalog: "영화·상영시간 서비스가 잠시 응답하지 않아요. 마이페이지와 예매 내역은 계속 볼 수 있어요.",
  booking: "예매 서비스가 잠시 응답하지 않아요. 영화·상영시간 조회는 계속 쓸 수 있어요.",
  user: "회원 서비스가 잠시 응답하지 않아요. 영화·상영시간 조회와 예매는 계속 쓸 수 있어요.",
};

function unavailableServiceMessage(error: ApiError): string | undefined {
  if (error.status !== 503 || error.code !== "SERVICE_UNAVAILABLE") return undefined;
  const routeId = error.details.find((d) => d.field === "service")?.value;
  if (typeof routeId !== "string") return undefined;
  const service = routeId.split("-")[0];
  return Object.hasOwn(UNAVAILABLE_BY_SERVICE, service) ? UNAVAILABLE_BY_SERVICE[service] : undefined;
}

/** 화면별 덮어쓰기(overrides)가 먼저, 그다음 멈춘 서비스 안내, 그다음 공통 문구 */
export function messageForError(error: unknown, overrides?: Record<string, string>): string {
  if (error instanceof ApiError) {
    return overrides?.[error.code] ?? unavailableServiceMessage(error) ?? MESSAGES[error.code] ?? FALLBACK;
  }
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
