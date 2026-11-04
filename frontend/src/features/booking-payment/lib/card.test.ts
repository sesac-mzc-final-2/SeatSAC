import { describe, expect, it } from "vitest";
import { digitsOnly, formatExpiry, hasCardError, toPayCard, validateCard } from "./card";

const valid = { number: "4242424242424242", expiry: "12/28", holder: "KIM" };

describe("validateCard", () => {
  it("올바른 입력이면 오류가 없다", () => {
    expect(hasCardError(validateCard(valid))).toBe(false);
  });

  it("공백·하이픈이 섞인 번호도 받아들인다", () => {
    expect(hasCardError(validateCard({ ...valid, number: "4242-4242 4242-4242" }))).toBe(false);
  });

  it("번호가 12자리 미만이면 오류 (계약 패턴과 같다)", () => {
    expect(validateCard({ ...valid, number: "42424242424" }).number).toContain("12~19");
  });

  it("유효기간은 MM/YY만", () => {
    expect(validateCard({ ...valid, expiry: "1228" }).expiry).toBeDefined();
    expect(validateCard({ ...valid, expiry: "13/28" }).expiry).toBeDefined();
    expect(validateCard({ ...valid, expiry: "00/28" }).expiry).toBeDefined();
    expect(validateCard({ ...valid, expiry: "01/28" }).expiry).toBeUndefined();
  });

  it("소유자 이름은 1~50자", () => {
    expect(validateCard({ ...valid, holder: "   " }).holder).toBeDefined();
    expect(validateCard({ ...valid, holder: "가".repeat(51) }).holder).toBeDefined();
  });
});

describe("입력 보조", () => {
  it("숫자만 남긴다", () => {
    expect(digitsOnly("4242-4242 42a42")).toBe("424242424242");
  });

  it("유효기간에 / 를 넣어 준다", () => {
    expect(formatExpiry("1")).toBe("1");
    expect(formatExpiry("12")).toBe("12");
    expect(formatExpiry("122")).toBe("12/2");
    expect(formatExpiry("12288")).toBe("12/28");
  });

  it("보낼 값은 번호에서 숫자만, 이름은 공백을 뗀다", () => {
    expect(toPayCard({ number: "4242 4242 4242 4242", expiry: "12/28", holder: " KIM " })).toEqual({
      number: "4242424242424242",
      expiry: "12/28",
      holder: "KIM",
    });
  });
});
