import { describe, expect, it } from "vitest";
import { timeSlotOfHour } from "./time-slot";

describe("timeSlotOfHour", () => {
  it.each([
    [0, "MORNING"],
    [11, "MORNING"],
    [12, "AFTERNOON"],
    [16, "AFTERNOON"],
    [17, "EVENING"],
    [20, "EVENING"],
    [21, "NIGHT"],
    [23, "NIGHT"],
  ] as const)("%i시 → %s (경계 12·17·21시는 다음 칸)", (hour, slot) => {
    expect(timeSlotOfHour(hour)).toBe(slot);
  });
});
