import type { TimeSlot } from "../model/types";

/** 와이어프레임 W8 시간대 칩 문구. 순서가 곧 화면 순서 */
export const TIME_SLOTS: ReadonlyArray<{ value: TimeSlot; label: string }> = [
  { value: "MORNING", label: "오전 ~12시" },
  { value: "AFTERNOON", label: "오후 12~17시" },
  { value: "EVENING", label: "저녁 17~21시" },
  { value: "NIGHT", label: "심야 21시~" },
];

/** 시작 시(0~23, KST) → 시간대. 경계는 위 문구와 같다 (12·17·21시는 다음 칸) */
export function timeSlotOfHour(hour: number): TimeSlot {
  if (hour < 12) return "MORNING";
  if (hour < 17) return "AFTERNOON";
  if (hour < 21) return "EVENING";
  return "NIGHT";
}

export const timeSlotLabel =(slot: TimeSlot): string => TIME_SLOTS.find((s) => s.value === slot)?.label ?? slot;
