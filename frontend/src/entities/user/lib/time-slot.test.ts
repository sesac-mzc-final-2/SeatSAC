import { describe, expect, it } from "vitest";
import { timeSlotOfHour } from "./time-slot";

describe("timeSlotOfHour", () => {
  it.each([
    [0, "NIGHT"],
    [5, "NIGHT"],
    [6, "MORNING"],
    [11, "MORNING"],
    [12, "AFTERNOON"],
    [16, "AFTERNOON"],
    [17, "EVENING"],
    [20, "EVENING"],
    [21, "NIGHT"],
    [23, "NIGHT"],
  ] as const)("%i시 → %s (경계 6·12·17·21시는 다음 칸, 0~5시는 전날 심야)", (hour, slot) => {
    expect(timeSlotOfHour(hour)).toBe(slot);
  });
});
