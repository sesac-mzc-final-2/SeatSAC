import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, NetworkError, buildUrl, configureHttpAuth, http } from "./http";

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
});
