import { afterEach, describe, expect, it, vi } from "vitest";
import { resetServerClock, serverNow } from "@/shared/lib/server-clock";
import { ApiError, NetworkError, buildUrl, configureHttpAuth, http, opsHttp } from "./http";

const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("http", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    configureHttpAuth({ getAccessToken: () => null, onUnauthorized: null, onSessionExpired: null });
  });

  it("buildUrl은 /api 접두사를 붙이고 빈 쿼리는 뺀다", () => {
    expect(buildUrl("/screenings", { date: "2026-11-16", theaterId: 4, movieId: undefined, x: "" })).toBe(
      "/api/screenings?date=2026-11-16&theaterId=4",
    );
  });

  it("opsHttp는 /api 없이 /ops 경로 그대로, 토큰을 붙여 보낸다 (gateway 아님, ADR-119)", async () => {
    configureHttpAuth({ getAccessToken: () => "admin-token" });
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, { items: [] }));
    await opsHttp("/ops/services", { query: { from: "2026-11-24" } });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("/ops/services?from=2026-11-24");
    expect((init?.headers as Record<string, string>).Authorization).toBe("Bearer admin-token");
  });

  it("opsHttp 403은 ApiError FORBIDDEN (ADMIN 그룹 아님)", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(403, { code: "FORBIDDEN", message: "x", traceId: "0123456789abcdef0123456789abcdef", details: [] }),
    );
    const error = await opsHttp("/ops/services").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(403);
    expect((error as ApiError).code).toBe("FORBIDDEN");
  });

  it("오류 응답(error.v1)을 ApiError로 바꾼다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(404, {
        code: "NOT_FOUND",
        message: "movie 999",
        traceId: "0123456789abcdef0123456789abcdef",
        details: [],
      }),
    );
    const error = await http("/movies/999").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(404);
    expect(apiError.code).toBe("NOT_FOUND");
    expect(apiError.traceId).toBe("0123456789abcdef0123456789abcdef");
    expect(apiError.details).toEqual([]);
  });

  it("Retry-After(초)를 ApiError에 담고, 숫자가 아니면 null", async () => {
    const body = { code: "AI_QUOTA_EXCEEDED", message: "x", traceId: "0123456789abcdef0123456789abcdef", details: [] };
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        new Response(JSON.stringify(body), { status: 429, headers: { "Content-Type": "application/json", "Retry-After": "42" } }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(body), {
          status: 429,
          headers: { "Content-Type": "application/json", "Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT" },
        }),
      );
    expect(((await http("/ai/chat").catch((e: unknown) => e)) as ApiError).retryAfterSeconds).toBe(42);
    expect(((await http("/ai/chat").catch((e: unknown) => e)) as ApiError).retryAfterSeconds).toBeNull();
  });

  it("본문이 오류 형식이 아니면 상태 코드로 code를 정한다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("Bad Gateway", { status: 502 }));
    const error = (await http("/movies").catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe("INTERNAL_ERROR");
    expect(error.traceId).toBeNull();
  });

  it("401이면 토큰을 1회 갱신해 재시도한다", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        jsonResponse(401, { code: "UNAUTHORIZED", message: "", traceId: "a".repeat(32), details: [] }),
      )
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }));
    configureHttpAuth({ getAccessToken: () => "old", onUnauthorized: async () => "new" });

    await expect(http("/users/me")).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const secondHeaders = (fetchMock.mock.calls[1]?.[1] as RequestInit).headers as Record<string, string>;
    expect(secondHeaders.Authorization).toBe("Bearer new");
  });

  it("갱신 뒤에도 401이면 세션 만료 처리기를 부르고 ApiError를 던진다", async () => {
    const unauthorized = (traceId: string) =>
      jsonResponse(401, { code: "UNAUTHORIZED", message: "", traceId, details: [] });
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(unauthorized("a".repeat(32)))
      .mockResolvedValueOnce(unauthorized("b".repeat(32)));
    const onSessionExpired = vi.fn();
    configureHttpAuth({ getAccessToken: () => "old", onUnauthorized: async () => "new", onSessionExpired });

    const error = (await http("/users/me").catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(401);
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("갱신이 실패(null)해도 세션 만료 처리기를 부른다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(401, { code: "UNAUTHORIZED", message: "", traceId: "c".repeat(32), details: [] }),
    );
    const onSessionExpired = vi.fn();
    configureHttpAuth({ getAccessToken: () => "old", onUnauthorized: async () => null, onSessionExpired });
    await expect(http("/users/me")).rejects.toBeInstanceOf(ApiError);
    expect(onSessionExpired).toHaveBeenCalledTimes(1);
  });

  it("auth: false면 Authorization을 붙이지 않는다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, { items: [] }));
    configureHttpAuth({ getAccessToken: () => "token" });
    await http("/theaters", { auth: false });
    const headers = (fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Record<string, string>;
    expect(headers.Authorization).toBeUndefined();
  });

  it("fetch가 던지면 NetworkError", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    await expect(http("/movies")).rejects.toBeInstanceOf(NetworkError);
  });

  it("응답의 Date 헤더로 서버 시각을 맞춘다 (선점 남은 시간 안내용)", async () => {
    const serverDate = new Date(Date.now() + 10 * 60_000);   // 서버가 10분 앞서 있다
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("{}", { status: 200, headers: { "Content-Type": "application/json", Date: serverDate.toUTCString() } }),
    );
    try {
      await http("/movies");
      expect(Math.abs(serverNow() - serverDate.getTime())).toBeLessThan(2000);
    } finally {
      resetServerClock();
    }
  });
});
