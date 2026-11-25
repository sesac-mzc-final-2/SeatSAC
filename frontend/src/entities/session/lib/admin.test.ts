import { describe, expect, it } from "vitest";
import { isAdminUser } from "./admin";

const user = (groups: string[]) => ({ id: "u", name: null, email: null, groups });

describe("isAdminUser", () => {
  it("ADMIN 그룹이 있을 때만 true (대소문자 구분)", () => {
    expect(isAdminUser(user(["ADMIN"]))).toBe(true);
    expect(isAdminUser(user(["VIP", "ADMIN"]))).toBe(true);
    expect(isAdminUser(user(["VIP"]))).toBe(false);
    expect(isAdminUser(user([]))).toBe(false);
    expect(isAdminUser(user(["admin"]))).toBe(false);
  });

  it("비로그인은 false", () => {
    expect(isAdminUser(null)).toBe(false);
    expect(isAdminUser(undefined)).toBe(false);
  });
});
