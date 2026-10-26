import type { ScreenType, Screening } from "../model/types";

const SCREEN_TYPE: Record<ScreenType, string> = { NORMAL: "일반", RECLINER: "리클라이너" };

export const screenTypeLabel = (type: ScreenType): string => SCREEN_TYPE[type] ?? type;

export type RemainingTone = "ok" | "low" | "soldout" | "unknown";

export interface RemainingLabel {
  text: string;
  tone: RemainingTone;
  /** 매진(잔여 0, Booking 집계)이면 선택 불가 */
  selectable: boolean;
}

/**
 * 잔여석 표기 (W3): BOOKING이면 `잔여 / 전체`, TOTAL(Booking 300ms 초과)이면 전체 좌석 수만 회색으로.
 * TOTAL일 때는 실제 잔여를 모르므로 매진 판단을 하지 않는다.
 */
export function remainingSeatsLabel(
  screening: Pick<Screening, "remainingSeats" | "totalSeats" | "remainingSeatsSource">,
): RemainingLabel {
  const { remainingSeats, totalSeats, remainingSeatsSource } = screening;
  if (remainingSeatsSource === "TOTAL") {
    return { text: `${totalSeats}석 (집계 지연)`, tone: "unknown", selectable: true };
  }
  if (remainingSeats <= 0) return { text: "매진", tone: "soldout", selectable: false };
  const low = totalSeats > 0 && remainingSeats / totalSeats <= 0.1;
  return { text: `${remainingSeats} / ${totalSeats}`, tone: low ? "low" : "ok", selectable: true };
}
