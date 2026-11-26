import { ApiError, messageForError } from "@/shared/api";

/**
 * 받기 결과를 화면 행동으로 나눈다 (계약 CPN-02).
 * - `issued` 받음(201) — 이미 받은 경우(409 COUPON_ALREADY_CLAIMED)도 결과는 같아 여기에 둔다
 * - `retry` 결과를 모른다(503·연결 끊김·본문 수신 실패·첫 요청 처리 중) — 발급이 이미 됐을 수 있어 <b>같은 Idempotency-Key</b>로 다시 보낸다
 * - `rejected` 서버가 거절했다 — 다음 누름은 새 키
 */
export type ClaimOutcome =
  | { kind: "issued"; already: boolean }
  | { kind: "retry"; message: string }
  | { kind: "rejected"; message: string };

export function claimOutcomeOf(error: unknown): ClaimOutcome {
  if (error instanceof ApiError) {
    switch (error.code) {
      case "COUPON_ALREADY_CLAIMED":
        return { kind: "issued", already: true };
      case "COUPON_NOT_ELIGIBLE":
        return { kind: "rejected", message: "VIP 회원만 받을 수 있어요. 최근 VIP가 됐다면 다시 로그인해 주세요." };
      case "COUPON_SOLD_OUT":
        return { kind: "rejected", message: "아쉽지만 선착순 쿠폰이 모두 소진됐어요." };
      case "COUPON_EVENT_NOT_OPEN":
        return {
          kind: "rejected",
          message: error.details.some((d) => d.reason === "CLOSED")
            ? "이벤트가 끝났어요."
            : "아직 이벤트 시작 전이에요. 시작 시각에 다시 눌러 주세요.",
        };
      case "IDEMPOTENCY_IN_PROGRESS":
        return { kind: "retry", message: "요청을 처리하고 있어요. 잠시 뒤 다시 눌러 주세요." };
      case "NOT_FOUND":
        return { kind: "rejected", message: "이벤트를 찾을 수 없어요." };
      default:
        if (error.status >= 500) return { kind: "retry", message: UNKNOWN_RESULT };
        return { kind: "rejected", message: messageForError(error) };
    }
  }
  // 서버의 오류 응답이 아닌 실패는 결과를 모른다 — 연결 끊김(NetworkError)뿐 아니라, 201 헤더를 받은 뒤
  // 본문을 읽다 끊긴 경우(공통 클라이언트가 그대로 던진다)도 발급은 커밋됐을 수 있어 같은 키로 다시 보낸다
  return { kind: "retry", message: UNKNOWN_RESULT };
}

const UNKNOWN_RESULT = "결과를 받지 못했어요. 다시 누르면 같은 요청으로 받았는지 확인해요.";
