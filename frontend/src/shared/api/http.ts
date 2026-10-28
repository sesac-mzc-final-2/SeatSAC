import { env } from "@/shared/config";

/** contracts/schemas/error.v1.json */
export interface ErrorDetail {
  field: string;
  value?: unknown;
  reason?: string;
}

export interface ErrorBody {
  code: string;
  message: string;
  traceId: string;
  details: ErrorDetail[];
}

/** 서버가 오류 응답(4xx·5xx)을 준 경우. 화면 분기는 `code`로, `message`는 표시하지 않는다(개발용). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly traceId: string | null;
  readonly details: ErrorDetail[];

  constructor(status: number, body: Partial<ErrorBody> | null) {
    super(body?.message ?? `HTTP ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = body?.code ?? (status >= 500 ? "INTERNAL_ERROR" : "UNKNOWN_ERROR");
    this.traceId = body?.traceId ?? null;
    this.details = body?.details ?? [];
  }
}

/** 응답 자체를 받지 못한 경우(연결 실패·DNS 등). traceId 없음. */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super("네트워크 오류", { cause });
    this.name = "NetworkError";
  }
}

type AccessTokenProvider = () => string | null;
/** 401을 받았을 때 토큰을 1회 갱신해 돌려준다. null이면 재시도하지 않는다 (2.2.5 공통 규칙) */
type UnauthorizedHandler = () => Promise<string | null>;
/** 갱신 실패 또는 재시도도 401 → 세션이 끝난 것. 인증 프로바이더가 로그인 화면으로 보낸다 ("또 401이면 로그인") */
type SessionExpiredHandler = () => void;

let getAccessToken: AccessTokenProvider = () => null;
let onUnauthorized: UnauthorizedHandler | null = null;
let onSessionExpired: SessionExpiredHandler | null = null;

/** app 계층(인증 프로바이더)이 토큰 공급자를 꽂는다. 화면 코드는 호출하지 않는다. */
export function configureHttpAuth(options: {
  getAccessToken?: AccessTokenProvider;
  onUnauthorized?: UnauthorizedHandler | null;
  onSessionExpired?: SessionExpiredHandler | null;
}): void {
  if (options.getAccessToken) getAccessToken = options.getAccessToken;
  if (options.onUnauthorized !== undefined) onUnauthorized = options.onUnauthorized;
  if (options.onSessionExpired !== undefined) onSessionExpired = options.onSessionExpired;
}

export type QueryValue = string | number | boolean | null | undefined;

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  query?: Record<string, QueryValue>;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  /** false면 Authorization 헤더를 붙이지 않는다 (catalog 조회 API) */
  auth?: boolean;
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
  }
  const qs = params.toString();
  return `${env.apiBaseUrl}${path}${qs ? `?${qs}` : ""}`;
}

async function parseErrorBody(response: Response): Promise<Partial<ErrorBody> | null> {
  try {
    const json: unknown = await response.json();
    if (json && typeof json === "object" && typeof (json as ErrorBody).code === "string") {
      return json as Partial<ErrorBody>;
    }
    return null;
  } catch {
    return null;
  }
}

/** `/api` 상대 경로로 호출하는 공통 클라이언트 (같은 출처, CORS 없음) */
export async function http<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", query, body, headers = {}, signal, auth = true } = options;

  const send = async (token: string | null): Promise<Response> => {
    const requestHeaders: Record<string, string> = { Accept: "application/json", ...headers };
    if (body !== undefined) requestHeaders["Content-Type"] = "application/json";
    if (token) requestHeaders.Authorization = `Bearer ${token}`;
    try {
      return await fetch(buildUrl(path, query), {
        method,
        headers: requestHeaders,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal,
      });
    } catch (error) {
      if (signal?.aborted) throw error;
      throw new NetworkError(error);
    }
  };

  let response = await send(auth ? getAccessToken() : null);

  if (response.status === 401 && auth && onUnauthorized) {
    const refreshed = await onUnauthorized().catch(() => null);
    if (refreshed) response = await send(refreshed);
    // 갱신이 안 되거나 새 토큰으로도 401 → 로그인 흐름 시작 (오류는 그대로 던져 화면도 알게 한다)
    if (response.status === 401) onSessionExpired?.();
  }

  if (!response.ok) throw new ApiError(response.status, await parseErrorBody(response));
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
