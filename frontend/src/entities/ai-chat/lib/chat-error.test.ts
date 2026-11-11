import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "@/shared/api";
import { chatErrorInfo } from "./chat-error";

const apiError = (status: number, code: string, details: ApiError["details"] = [], retryAfter: number | null = null) =>
  new ApiError(status, { code, message: "dev", traceId: "0123456789abcdef0123456789abcdef", details }, retryAfter);

describe("chatErrorInfo", () => {
  it("429 분 한도는 Retry-After 초만큼 막는다", () => {
    const info = chatErrorInfo(
      apiError(429, "AI_QUOTA_EXCEEDED", [{ field: "quota", value: "10/min", reason: "PER_MINUTE" }], 15),
    );
    expect(info).toEqual({ message: expect.stringContaining("15초"), retryable: false, cooldownSeconds: 15 });
  });

  it("429 분 한도에 Retry-After 가 없으면 60초", () => {
    const info = chatErrorInfo(apiError(429, "AI_QUOTA_EXCEEDED", [{ field: "quota", reason: "PER_MINUTE" }]));
    expect(info.cooldownSeconds).toBe(60);
  });

  it("429 일 한도는 내일 안내", () => {
    const info = chatErrorInfo(apiError(429, "AI_QUOTA_EXCEEDED", [{ field: "quota", reason: "PER_DAY" }], 3600));
    expect(info.message).toContain("내일");
    expect(info.retryable).toBe(false);
  });

  it("503 AI_LLM_UNAVAILABLE 은 reason 으로 문구를 나눈다", () => {
    const reason = (r: string) => [{ field: "provider", value: "anthropic", reason: r }];
    expect(chatErrorInfo(apiError(503, "AI_LLM_UNAVAILABLE", reason("TIMEOUT"))).message).toContain("오래");
    expect(chatErrorInfo(apiError(503, "AI_LLM_UNAVAILABLE", reason("SPEND_LIMIT"))).retryable).toBe(false);
    expect(chatErrorInfo(apiError(503, "AI_LLM_UNAVAILABLE", reason("OVERLOADED"))).retryable).toBe(true);
  });

  it("503 SERVICE_UNAVAILABLE upstream user 는 예매 내역 안내", () => {
    const info = chatErrorInfo(apiError(503, "SERVICE_UNAVAILABLE", [{ field: "upstream", value: "user" }]));
    expect(info.message).toContain("예매 내역");
  });

  it("409·네트워크 오류는 다시 묻기 가능", () => {
    expect(chatErrorInfo(apiError(409, "AI_QUERY_IN_PROGRESS")).retryable).toBe(true);
    expect(chatErrorInfo(new NetworkError(new Error("x"))).retryable).toBe(true);
  });
});
