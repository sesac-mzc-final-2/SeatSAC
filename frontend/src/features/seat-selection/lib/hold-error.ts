import { ApiError, messageForError } from "@/shared/api";

/**
 * BKG-02 선점 실패를 화면이 분기할 수 있는 모양으로 바꾼다.
 * 분기는 `code`로만 한다 — 서버 `message`는 개발용이라 그대로 보여 주지 않는다.
 */
export type HoldFailure =
  /** 고르는 사이에 누가 먼저 잡았다 → 안내 + 좌석 상태 다시 받기 */
  | { kind: "seat-taken"; seatIds: number[] }
  /** 이 회차에 진행 중인 선점이 이미 있다 → 이어서 결제하거나 해제 */
  | { kind: "active-hold"; bookingId: number | null; expiresAt: string | null }
  | { kind: "screening-started" }
  /** 같은 멱등 키 요청이 아직 처리 중 → 잠시 뒤 다시 */
  | { kind: "in-progress" }
  | { kind: "login-required" }
  | { kind: "not-found" }
  | { kind: "unavailable" }
  | { kind: "unknown"; message: string };

const numbersIn = (error: ApiError, field: string): number[] =>
  error.details
    .filter((detail) => detail.field === field && typeof detail.value === "number")
    .map((detail) => detail.value as number);

const stringIn = (error: ApiError, field: string): string | null => {
  const found = error.details.find((detail) => detail.field === field && typeof detail.value === "string");
  return found ? (found.value as string) : null;
};

export function interpretHoldError(error: unknown): HoldFailure {
  if (!(error instanceof ApiError)) return { kind: "unknown", message: messageForError(error) };

  switch (error.code) {
    case "SEAT_UNAVAILABLE":
      return { kind: "seat-taken", seatIds: numbersIn(error, "seatIds") };
    case "ACTIVE_HOLD_EXISTS":
      return {
        kind: "active-hold",
        bookingId: numbersIn(error, "bookingId")[0] ?? null,
        expiresAt: stringIn(error, "expiresAt"),
      };
    case "SCREENING_STARTED":
      return { kind: "screening-started" };
    case "IDEMPOTENCY_IN_PROGRESS":
      return { kind: "in-progress" };
    case "UNAUTHORIZED":
      return { kind: "login-required" };
    case "NOT_FOUND":
      return { kind: "not-found" };
    case "SERVICE_UNAVAILABLE":
    case "CATALOG_UNAVAILABLE":
      return { kind: "unavailable" };
    default:
      return { kind: "unknown", message: messageForError(error) };
  }
}

/** 선점 실패 안내 문구 */
export function holdFailureMessage(failure: HoldFailure): string {
  switch (failure.kind) {
    case "seat-taken":
      return "방금 다른 분이 먼저 선택한 좌석이 있습니다. 좌석 상태를 다시 불러왔어요.";
    case "active-hold":
      return "이미 선택해 둔 좌석이 있습니다. 결제를 이어가거나 선택을 취소해 주세요.";
    case "screening-started":
      return "이미 시작된 상영입니다. 다른 회차를 선택해 주세요.";
    case "in-progress":
      return "직전 요청을 처리하고 있습니다. 잠시 후 다시 시도해 주세요.";
    case "login-required":
      return "로그인이 필요합니다.";
    case "not-found":
      return "회차 정보를 찾을 수 없습니다.";
    case "unavailable":
      return "좌석 예약이 잠시 불가합니다. 잠시 후 다시 시도해 주세요.";
    case "unknown":
      return failure.message;
  }
}
