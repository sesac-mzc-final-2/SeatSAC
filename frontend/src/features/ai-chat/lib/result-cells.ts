import type { ChatCell, ChatColumn } from "@/entities/ai-chat";
import { screenTypeLabel } from "@/entities/screening";
import { bookingStatusLabel, type MyBookingStatus } from "@/entities/user";
import { formatWon } from "@/shared/lib";

/**
 * 결과 표 머리글. 열 이름은 SQL 별칭이라 NL2SQL 은 아무 이름이나 올 수 있다 → 아는 이름만 한국어로, 나머지는 그대로.
 * 스냅샷 컬럼은 docs/data-model.md 6-1
 */
const COLUMN_LABELS: Record<string, string> = {
  movie_title: "영화",
  title: "영화",
  title_en: "영문 제목",
  theater_name: "극장",
  name: "이름",
  region: "지역",
  auditorium_name: "상영관",
  screen_type: "상영관 종류",
  film_format: "상영 방식",
  business_date: "날짜",
  starts_at: "시작",
  ends_at: "종료",
  start_hour: "시작 시",
  remaining_seats: "잔여석",
  total_seats: "전체 좌석",
  remaining_ratio: "잔여 비율",
  view_grade: "관람 등급",
  genres: "장르",
  director: "감독",
  release_date: "개봉일",
  booking_id: "예매 번호",
  status: "상태",
  seat_labels: "좌석",
  amount: "금액",
  count: "개수",
  screening_count: "회차 수",
};

export const columnLabel = (column: Pick<ChatColumn, "name">): string => COLUMN_LABELS[column.name] ?? column.name;

/** 숫자 열은 오른쪽 정렬 */
export const isNumericColumn = (column: Pick<ChatColumn, "type">): boolean => column.type !== "text";

const KST_MINUTE = /^\d{4}-(\d{2})-(\d{2}) (\d{2}:\d{2})$/;
const BOOKING_STATUSES = new Set(["CONFIRMED", "PAYMENT_FAILED", "EXPIRED", "CANCELED"]);

/** 셀 표시. 시각은 스냅샷의 KST 문자열(`YYYY-MM-DD HH:MM`)이라 시간대 변환 없이 잘라서 보여 준다 */
export function formatCell(column: Pick<ChatColumn, "name" | "type">, value: ChatCell): string {
  if (value === null) return "–";
  if (typeof value === "number") {
    if (column.name === "amount") return formatWon(value);
    if (column.name.endsWith("_ratio")) return `${Math.round(value * 1000) / 10}%`;
    return value.toLocaleString("ko-KR");
  }
  const time = KST_MINUTE.exec(value);
  if (time) return `${Number(time[1])}/${Number(time[2])} ${time[3]}`;
  if (column.name === "status" && BOOKING_STATUSES.has(value)) return bookingStatusLabel(value as MyBookingStatus);
  if (column.name === "screen_type" && (value === "NORMAL" || value === "RECLINER")) return screenTypeLabel(value);
  return value;
}
