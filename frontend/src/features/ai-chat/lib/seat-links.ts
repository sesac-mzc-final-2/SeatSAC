import type { ChatResponse } from "@/entities/ai-chat";
import { routes } from "@/shared/config";

/**
 * 답의 표에서 회차 한 줄 → 그 회차의 좌석 선택 화면(W4)으로 넘기기.
 * 회차 ID는 ai 가 `screeningIds`(`rows` 와 같은 순서)로 준다 — 화면 경로는 여기서 `routes` 로 만든다.
 * 열을 더 두지 않고 표의 시작 시각(`starts_at`) 칸을 링크로 만든다(좁은 대화창의 표가 더 좁아지지 않게).
 * 집계 결과처럼 행이 회차 하나가 아니면 ai 가 null 을 주고, 그러면 링크가 없다.
 *
 * 로그인은 좌석 화면의 `RequireLogin` 이 확인한다(챗봇은 로그인한 사람에게만 보인다).
 */
export type SeatLink = { kind: "link"; href: string } | { kind: "blocked"; text: "매진" | "마감" };

/** 좌석 링크를 거는 열 — 이 칸의 시각 글자가 링크가 된다 */
export const SEAT_LINK_COLUMN = "starts_at";

/** 예매 마감: 상영 시작 5분 전부터 선점이 `SCREENING_STARTED` 로 막힌다 (booking.openapi.yaml BKG-02) */
const BOOKING_CLOSE_MS = 5 * 60 * 1000;

const KST_MINUTE = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})$/;

/**
 * 스냅샷의 KST 문자열(`YYYY-MM-DD HH:MM`) → epoch ms. 모양이 다르면 null.
 * 의도: 형식이 다른 시각 문자열은 마감 판정 없이 링크를 유지한다(좌석 화면이 막는다) — `result-cells` 의 표시용 파서와 일부러 따로 둔다
 */
function startsAtMs(value: unknown): number | null {
  const match = typeof value === "string" ? KST_MINUTE.exec(value) : null;
  return match ? Date.parse(`${match[1]}T${match[2]}:00+09:00`) : null;
}

/**
 * 행마다 좌석 화면 링크(없으면 null). 표 전체에 줄 것이 없으면 null.
 * 시작 시각 열이 표에 없으면 링크를 걸 칸이 없어 null (ai 규칙상 없어야 하는 경우 — 방어)
 * - 잔여석이 0 이하로 확인된 회차 → 「매진」(상영시간표 `remainingSeatsLabel` 과 같은 기준).
 *   잔여석을 모르면(null·열 없음) 링크를 둔다. 전제: 집계 지연(TOTAL 출처)인 줄은 ai 가 잔여석을 null 로 비워 보낸다 —
 *   그 경우만 "모름"으로 본다(숫자가 오면 확인된 값으로 믿는다)
 * - 지금(`now`, 서버 시각 ms)이 시작 5분 전을 지났으면 → 「마감」. 답은 대화창에 남아 있어 받은 뒤에 마감될 수 있다
 */
export function seatLinks(
  response: Pick<ChatResponse, "columns" | "rows" | "screeningIds">,
  now: number,
): (SeatLink | null)[] | null {
  const { columns, rows, screeningIds } = response;
  // 길이가 다르면 어느 행의 ID 인지 믿을 수 없다 → 링크를 두지 않는다
  if (!columns || !rows || !screeningIds || screeningIds.length !== rows.length) return null;
  const index = (name: string) => columns.findIndex((column) => column.name === name);
  const startsAt = index(SEAT_LINK_COLUMN);
  if (startsAt < 0) return null;
  const remaining = index("remaining_seats");

  const links = rows.map((row, rowIndex): SeatLink | null => {
    const screeningId = screeningIds[rowIndex];
    // 주소에 넣을 값이라 양의 정수만 받는다(문자열·소수·0 은 회차 ID 가 아니다)
    if (typeof screeningId !== "number" || !Number.isSafeInteger(screeningId) || screeningId <= 0) return null;
    const seats = remaining >= 0 ? row[remaining] : null;
    if (typeof seats === "number" && seats <= 0) return { kind: "blocked", text: "매진" };
    // 시각이 비어 있으면 링크로 만들 글자가 없다
    if (row[startsAt] === null || row[startsAt] === "") return null;
    const start = startsAtMs(row[startsAt]);
    if (start !== null && now >= start - BOOKING_CLOSE_MS) return { kind: "blocked", text: "마감" };
    return { kind: "link", href: routes.screening(screeningId) };
  });
  return links.some((link) => link !== null) ? links : null;
}
