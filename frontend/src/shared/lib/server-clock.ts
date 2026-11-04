/**
 * 서버 기준 지금 시각. 선점 만료처럼 <b>서버가 정한 시각</b>까지 남은 시간을 셀 때 쓴다 —
 * 사용자 컴퓨터 시계가 틀려도 화면 안내(남은 시간·곧 끝남)가 서버 판단과 맞게.
 *
 * 차이는 API 응답의 `Date` 헤더로 맞춘다(공통 HTTP 클라이언트가 응답마다 부른다). 헤더가 초 단위라
 * 오차는 1초 안팎이다. 결제 가능 여부는 여전히 서버가 서버 시계로 정한다 — 이 값은 안내용이다.
 */
let offsetMs = 0;

/** 왕복이 이보다 길면 서버가 헤더를 찍은 때를 가늠하기 어려워 쓰지 않는다 */
const MAX_ROUND_TRIP_MS = 5000;

/**
 * @param dateHeader 응답의 `Date` 헤더 (없으면 아무것도 안 한다)
 * @param sentAt 요청을 보낸 내 시각 (ms)
 * @param receivedAt 응답을 받은 내 시각 (ms)
 * @param cachedAge 응답의 `Age` 헤더 — 캐시가 준 응답이면 헤더 시각이 예전 것이라 쓰지 않는다
 */
export function syncServerClock(
  dateHeader: string | null,
  sentAt: number,
  receivedAt: number,
  cachedAge: string | null = null,
): void {
  if (!dateHeader || (cachedAge !== null && Number(cachedAge) > 0)) return;
  if (receivedAt - sentAt > MAX_ROUND_TRIP_MS) return;
  const server = Date.parse(dateHeader);
  if (!Number.isFinite(server)) return;
  // 헤더는 초 단위로 잘려 있어 평균 0.5초를 더하고, 서버가 찍은 때는 왕복의 가운데로 본다
  offsetMs = server + 500 - (sentAt + receivedAt) / 2;
}

/** 서버 기준 지금 시각 (ms). 아직 응답을 못 받았으면 내 시계 그대로 */
export const serverNow = (): number => Date.now() + offsetMs;

/** 테스트용 — 차이를 지운다 */
export function resetServerClock(): void {
  offsetMs = 0;
}
