import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/shared/config", () => ({
  env: { cognito: { domain: "https://cinema-staging.auth.example.com", clientId: "client-1" } },
}));

const { revokeRefreshToken } = await import("./revoke-refresh-token");

describe("revokeRefreshToken (로그아웃 토큰 폐기)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("/oauth2/revoke 에 token·client_id 를 폼으로 보내고 제한 시간 signal 을 붙인다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(null, { status: 200 }));
    await revokeRefreshToken("rt-abc");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://cinema-staging.auth.example.com/oauth2/revoke");
    expect(init.method).toBe("POST");
    expect(String(init.body)).toBe("token=rt-abc&client_id=client-1");
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it("토큰이 없으면 아무것도 보내지 않는다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    await revokeRefreshToken(undefined);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("실패·중단은 삼킨다 (로그아웃을 막지 않음)", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new DOMException("timeout", "TimeoutError"));
    await expect(revokeRefreshToken("rt-abc")).resolves.toBeUndefined();
  });
});
