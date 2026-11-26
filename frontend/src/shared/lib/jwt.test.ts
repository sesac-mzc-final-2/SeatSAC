import { describe, expect, it } from "vitest";
import { cognitoGroupsOf, decodeJwtPayload } from "./jwt";

const encode = (value: unknown) =>
  btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
const token = (payload: unknown) => `${encode({ alg: "RS256" })}.${encode(payload)}.sig`;

describe("decodeJwtPayload", () => {
  it("base64url 본문을 읽는다 (패딩 없음·한글)", () => {
    expect(decodeJwtPayload(token({ sub: "a", name: "한채연", "cognito:groups": ["ADMIN"] }))).toEqual({
      sub: "a",
      name: "한채연",
      "cognito:groups": ["ADMIN"],
    });
  });

  it("형식이 틀리면 null", () => {
    expect(decodeJwtPayload(null)).toBeNull();
    expect(decodeJwtPayload("")).toBeNull();
    expect(decodeJwtPayload("abc")).toBeNull();
    expect(decodeJwtPayload("a.!!!.c")).toBeNull();
    expect(decodeJwtPayload(`a.${encode([1, 2])}.c`)).toBeNull();
  });
});

describe("cognitoGroupsOf", () => {
  it("문자열 그룹만, 없으면 빈 배열", () => {
    expect(cognitoGroupsOf({ "cognito:groups": ["VIP", 1, "ADMIN"] })).toEqual(["VIP", "ADMIN"]);
    expect(cognitoGroupsOf({ "cognito:groups": "ADMIN" })).toEqual([]);
    expect(cognitoGroupsOf({})).toEqual([]);
    expect(cognitoGroupsOf(null)).toEqual([]);
  });
});
