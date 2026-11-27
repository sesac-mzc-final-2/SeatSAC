/** `datetime-local` 입력 값(`YYYY-MM-DDTHH:mm`)을 KST로 읽는다 — 관리자 화면의 시각은 모두 KST(conventions 6장) */
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

/** 지금 이후의 가장 가까운 정분(KST)을 입력 값 형식으로 — 시작 시각 기본값 */
export function nextMinuteKstInput(now: number = Date.now()): string {
  const next = Math.ceil(now / 60_000) * 60_000;
  return new Date(next + KST_OFFSET_MS).toISOString().slice(0, 16);
}

/** 입력 값이 `YYYY-MM-DDTHH:mm`(초가 붙어도 됨) 모양인지 */
export function isKstInput(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(value);
}

/** `2026-11-27T14:00` → `2026-11-27T14:00:00+09:00`. 브라우저·자동완성이 초(`HH:mm:ss`)를 붙여도 분까지만 쓴다 */
export function kstInputToIso(value: string): string {
  return `${value.slice(0, 16)}:00+09:00`;
}
