import { describe, expect, it } from "vitest";
import { messageForError } from "./error-message";
import { ApiError, NetworkError, type ErrorDetail } from "./http";

const TRACE = "0123456789abcdef0123456789abcdef";

/** gateway GatewayErrorHandler가 주는 503 — value는 라우트 ID */
const gateway503 = (routeId: string, reason = "UPSTREAM_UNAVAILABLE") =>
  new ApiError(503, {
    code: "SERVICE_UNAVAILABLE",
    message: "x",
    traceId: TRACE,
    details: [{ field: "service", value: routeId, reason }],
  });

const apiError = (status: number, code: string, details: ErrorDetail[] = []) =>
  new ApiError(status, { code, message: "x", traceId: TRACE, details });

describe("messageForError — 멈춘 서비스 안내 (5.1.5)", () => {
  it("booking이 멈추면 조회는 계속 된다고 알린다 — 라우트 ID booking-* 모두", () => {
    for (const routeId of ["booking", "booking-seats", "booking-seat-stream"]) {
      expect(messageForError(gateway503(routeId))).toBe(
        "예매 서비스가 잠시 응답하지 않아요. 영화·상영시간 조회는 계속 쓸 수 있어요.",
      );
    }
  });

  it("catalog·user도 서비스별 문구", () => {
    expect(messageForError(gateway503("catalog"))).toContain("영화·상영시간 서비스가 잠시 응답하지 않아요");
    expect(messageForError(gateway503("user", "UPSTREAM_TIMEOUT"))).toContain("회원 서비스가 잠시 응답하지 않아요");
  });

  it("모르는 서비스·service 정보가 없으면 공통 문구", () => {
    const common = "서비스가 잠시 응답하지 않습니다. 다시 시도해 주세요.";
    expect(messageForError(gateway503("payment-webhook"))).toBe(common);
    expect(messageForError(gateway503("constructor"))).toBe(common); // 객체 기본 속성 이름에 걸리지 않는다
    expect(messageForError(apiError(503, "SERVICE_UNAVAILABLE"))).toBe(common);
    expect(messageForError(apiError(503, "SERVICE_UNAVAILABLE", [{ field: "upstream", value: "user" }]))).toBe(
      common,
    );
  });

  it("화면별 덮어쓰기가 서비스 안내보다 먼저다", () => {
    const overrides = { SERVICE_UNAVAILABLE: "결제 취소가 잠시 지연되고 있어요. 잠시 후 다시 시도해 주세요." };
    expect(messageForError(gateway503("booking"), overrides)).toBe(overrides.SERVICE_UNAVAILABLE);
  });

  it("503이 아닌 오류·다른 코드는 그대로", () => {
    expect(messageForError(apiError(503, "BOOKING_UNAVAILABLE", [{ field: "service", value: "booking" }]))).toBe(
      "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
    );
    expect(messageForError(apiError(404, "NOT_FOUND"))).toBe("요청한 정보를 찾을 수 없습니다.");
    expect(messageForError(new NetworkError(new Error("x")))).toBe(
      "네트워크에 연결할 수 없습니다. 연결 상태를 확인해 주세요.",
    );
  });
});
