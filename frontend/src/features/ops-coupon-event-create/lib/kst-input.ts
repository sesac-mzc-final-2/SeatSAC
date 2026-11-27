/** `datetime-local` 입력 값(`YYYY-MM-DDTHH:mm`)을 KST로 읽는다 — 관리자 화면의 시각은 모두 KST(conventions 6장) */
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** 지금 이후의 가장 가까운 정분(KST)을 입력 값 형식으로 — 시작 시각 기본값 */
export function nextMinuteKstInput(now: number = Date.now()): string {
  const next = Math.ceil(now / 60_000) * 60_000;
  return new Date(next + KST_OFFSET_MS).toISOString().slice(0, 16);
}

/** `2026-11-27T14:00` → `2026-11-27T14:00:00+09:00` */
export function kstInputToIso(value: string): string {
  return `${value}:00+09:00`;
}
