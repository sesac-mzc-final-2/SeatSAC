/** 모의 카드 규칙 (ADR-13) — 화면에 안내로 보여 준다 */
export const MOCK_CARDS = [
  { suffix: "0000", label: "카드 거절" },
  { suffix: "1111", label: "잔액 부족" },
  { suffix: "9999", label: "5초 지연 후 승인" },
] as const;

/** 계약의 패턴과 같다 (contracts/openapi/booking.openapi.yaml PayRequest) */
const NUMBER_PATTERN = /^[0-9]{12,19}$/;
const EXPIRY_PATTERN = /^(0[1-9]|1[0-2])\/[0-9]{2}$/;

export interface CardInput {
  number: string;
  expiry: string;
  holder: string;
}

export type CardField = keyof CardInput;

/** 입력 오류 — 보내기 전에 화면에서 먼저 잡는다(서버 400을 기다리지 않게) */
export type CardErrors = Partial<Record<CardField, string>>;

/** 숫자만 남긴다. 입력칸에 공백·하이픈을 넣어도 받아들인다 */
export const digitsOnly = (value: string): string => value.replace(/\D/g, "");

/** `1228` → `12/28` (입력하는 동안 자동으로) */
export function formatExpiry(value: string): string {
  const digits = digitsOnly(value).slice(0, 4);
  return digits.length <= 2 ? digits : `${digits.slice(0, 2)}/${digits.slice(2)}`;
}

export function validateCard(input: CardInput): CardErrors {
  const errors: CardErrors = {};
  const number = digitsOnly(input.number);
  if (!NUMBER_PATTERN.test(number)) errors.number = "카드 번호 12~19자리를 입력해 주세요.";
  if (!EXPIRY_PATTERN.test(input.expiry)) errors.expiry = "유효기간을 MM/YY로 입력해 주세요.";
  const holder = input.holder.trim();
  if (holder.length < 1 || holder.length > 50) errors.holder = "카드 소유자 이름을 입력해 주세요.";
  return errors;
}

export const hasCardError = (errors: CardErrors): boolean => Object.keys(errors).length > 0;

/** 보낼 값으로 정리 (번호는 숫자만, 이름은 앞뒤 공백 제거) */
export const toPayCard = (input: CardInput): CardInput => ({
  number: digitsOnly(input.number),
  expiry: input.expiry,
  holder: input.holder.trim(),
});
