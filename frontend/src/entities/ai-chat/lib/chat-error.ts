import { ApiError, messageForError } from "@/shared/api";

export interface ChatErrorInfo {
  message: string;
  /** 같은 질문을 다시 보내 볼 만한지 (한도 초과·입력 오류는 아님) */
  retryable: boolean;
  /** 이 시간(초) 동안 입력을 막는다 (429 분 한도). null 이면 막지 않는다 */
  cooldownSeconds: number | null;
}

const reasonOf = (error: ApiError): string | undefined => error.details.find((d) => d.reason)?.reason;

/** 429 `details[{ field: "quota", value: "10/min" | "100/day" }]` 의 숫자. 서버 설정(APP_AI_LIMIT_*)이 바뀌어도 문구가 맞게 */
function quotaLimitOf(error: ApiError, fallback: number): number {
  const value = error.details.find((d) => d.field === "quota")?.value;
  const match = typeof value === "string" ? /^(\d+)\//.exec(value) : null;
  return match ? Number(match[1]) : fallback;
}

/** 챗봇 오류 문구 (ai.openapi.yaml 409·429·503). `message` 는 개발용이라 쓰지 않고 code·reason 으로 고른다 */
export function chatErrorInfo(error: unknown): ChatErrorInfo {
  if (!(error instanceof ApiError)) {
    return { message: messageForError(error), retryable: true, cooldownSeconds: null };
  }
  const reason = reasonOf(error);
  switch (error.code) {
    case "VALIDATION_FAILED":
      return { message: "질문은 1~300자로 입력해 주세요.", retryable: false, cooldownSeconds: null };
    case "AI_QUERY_IN_PROGRESS":
      return {
        message: "앞선 질문에 아직 답하고 있어요. 잠시 뒤 다시 물어봐 주세요.",
        retryable: true,
        cooldownSeconds: null,
      };
    case "AI_QUOTA_EXCEEDED": {
      // 일 한도의 Retry-After 는 KST 자정까지(최대 86,400초)라 입력을 잠그지 않는다 — 문구로만 안내
      if (reason === "PER_DAY") {
        return {
          message: `오늘 질문 한도(${quotaLimitOf(error, 100)}회)를 모두 썼어요. 내일 다시 이용해 주세요.`,
          retryable: false,
          cooldownSeconds: null,
        };
      }
      const seconds = error.retryAfterSeconds ?? 60;
      return {
        message: `질문이 너무 잦아요(분당 ${quotaLimitOf(error, 10)}회). ${seconds}초 뒤에 다시 물어봐 주세요.`,
        retryable: false,
        cooldownSeconds: seconds,
      };
    }
    case "AI_LLM_UNAVAILABLE":
      if (reason === "TIMEOUT") {
        return {
          message: "답을 만드는 데 시간이 너무 오래 걸렸어요. 잠시 뒤 다시 시도해 주세요.",
          retryable: true,
          cooldownSeconds: null,
        };
      }
      if (reason === "SPEND_LIMIT") {
        return {
          message: "이번 달 AI 사용량을 모두 써서 지금은 답할 수 없어요.",
          retryable: false,
          cooldownSeconds: null,
        };
      }
      return { message: "AI가 잠시 응답하지 않아요. 잠시 뒤 다시 시도해 주세요.", retryable: true, cooldownSeconds: null };
    case "AI_SNAPSHOT_NOT_READY":
      return {
        message: "상영 정보를 불러오는 중이에요. 잠시 뒤 다시 시도해 주세요.",
        retryable: true,
        cooldownSeconds: null,
      };
    case "SERVICE_UNAVAILABLE":
      if (error.details.some((d) => d.field === "upstream" && d.value === "user")) {
        return {
          message: "예매 내역을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.",
          retryable: true,
          cooldownSeconds: null,
        };
      }
      return { message: "AI 챗봇이 지금은 쉬고 있어요. 잠시 뒤 다시 시도해 주세요.", retryable: true, cooldownSeconds: null };
    default:
      return { message: messageForError(error), retryable: error.status >= 500, cooldownSeconds: null };
  }
}
