/**
 * 날짜·시각 표시 규칙 (docs/conventions.md 6장):
 * - API의 시각은 ISO-8601 `+09:00`. 화면은 그 값을 KST로 그대로 보여 준다 (브라우저 시간대 무관).
 * - `date` 파라미터·영업일은 Asia/Seoul 기준 `YYYY-MM-DD`.
 */
const KST = "Asia/Seoul";
const DOW_KO = ["일", "월", "화", "수", "목", "금", "토"] as const;
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

const kstDateFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: KST,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/** 오늘의 KST 영업일 `YYYY-MM-DD`. 로케일 출력 형식에 기대지 않고 부분(year·month·day)을 직접 조합한다 */
export function todayKst(now: Date = new Date()): string {
  const parts: Partial<Record<Intl.DateTimeFormatPartTypes, string>> = {};
  for (const part of kstDateFormatter.formatToParts(now)) parts[part.type] = part.value;
  return `${parts.year}-${parts.month}-${parts.day}`;
}

export function isValidIsoDate(value: string | null | undefined): value is string {
  if (!value || !ISO_DATE.test(value)) return false;
  const [y, m, d] = value.split("-").map(Number);
  const date = new Date(Date.UTC(y, m - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d;
}

function toUtcDate(isoDate: string): Date {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function fromUtcDate(date: Date): string {
  const y = date.getUTCFullYear();
  const m = String(date.getUTCMonth() + 1).padStart(2, "0");
  const d = String(date.getUTCDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

export function addDays(isoDate: string, days: number): string {
  const date = toUtcDate(isoDate);
  date.setUTCDate(date.getUTCDate() + days);
  return fromUtcDate(date);
}

/** 요일 한 글자 (일·월·…·토) */
export function dayOfWeekKo(isoDate: string): string {
  return DOW_KO[toUtcDate(isoDate).getUTCDay()];
}

export function isWeekend(isoDate: string): boolean {
  const dow = toUtcDate(isoDate).getUTCDay();
  return dow === 0 || dow === 6;
}

/** `2026-11-16T14:30:00+09:00` → `14:30` (오프셋이 +09:00이라 문자열 그대로 KST) */
export function timeOfIso(dateTime: string): string {
  const match = /T(\d{2}:\d{2})/.exec(dateTime);
  return match?.[1] ?? dateTime;
}

/** `2026-11-16` → `11월 16일 (월)` */
export function formatDateKo(isoDate: string, options: { withYear?: boolean } = {}): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  const base = `${m}월 ${d}일 (${dayOfWeekKo(isoDate)})`;
  return options.withYear ? `${y}년 ${base}` : base;
}

/** `2026-11-16` → `2026.11.16` (상세 개봉일) */
export function formatDateDots(isoDate: string): string {
  return isoDate.replaceAll("-", ".");
}
