import { describe, expect, it } from "vitest";
import { decodeJwtPayload, roleFromAccessToken } from "./jwt";

const encode = (payload: object) => {
  const bytes = new TextEncoder().encode(JSON.stringify(payload));
  const binary = Array.from(bytes, (b) => String.fromCharCode(b)).join("");
  return `h.${btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}.s`;
};

describe("jwt", () => {
  it("payload를 읽는다 (base64url, 한글 포함)", () => {
    const token = encode({ sub: "u-1", name: "홍길동", "cognito:groups": ["ADMIN"] });
    expect(decodeJwtPayload(token)).toMatchObject({ sub: "u-1", name: "홍길동" });
  });

  it("ADMIN 그룹이 있으면 ADMIN, 없거나 깨진 토큰이면 USER", () => {
    expect(roleFromAccessToken(encode({ "cognito:groups": ["ADMIN"] }))).toBe("ADMIN");
    expect(roleFromAccessToken(encode({ "cognito:groups": ["USER"] }))).toBe("USER");
    expect(roleFromAccessToken(encode({}))).toBe("USER");
    expect(roleFromAccessToken("not-a-jwt")).toBe("USER");
    expect(roleFromAccessToken(null)).toBe("USER");
  });
});
