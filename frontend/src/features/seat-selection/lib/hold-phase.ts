/** 이만큼 남으면 「곧 끝나요」를 띄운다 */
export const ENDING_SOON_MS = 60_000;

/** unknown 만료 시각 모름 · open 여유 · ending 60초 이하 · over 지남 */
export type HoldPhase = "unknown" | "open" | "ending" | "over";

/** 선점 단계. `now`는 서버 기준 시각(serverNow)을 넘긴다 */
export function holdPhase(expiresAt: string | null, now: number): HoldPhase {
  if (!expiresAt) return "unknown";
  const left = new Date(expiresAt).getTime() - now;
  if (!Number.isFinite(left)) return "unknown";
  if (left <= 0) return "over";
  return left <= ENDING_SOON_MS ? "ending" : "open";
}
