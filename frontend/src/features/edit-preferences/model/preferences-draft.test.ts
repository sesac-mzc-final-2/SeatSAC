import { describe, expect, it } from "vitest";
import type { Preferences } from "@/entities/user";
import {
  isSameDraft,
  isTheaterLimitReached,
  moveTheater,
  setTimeSlot,
  toDraft,
  toPreferences,
  toggleGenre,
  toggleTheater,
} from "./preferences-draft";

const saved: Preferences = {
  preferredTheaters: [
    { rank: 2, theaterId: 18 },
    { rank: 1, theaterId: 4 },
  ],
  preferredTimeSlot: "EVENING",
  preferredGenres: ["드라마", "모험"],
};

describe("preferences-draft (USR-05 선호 편집)", () => {
  it("rank 순서로 펼치고, 저장할 때 순서를 rank 1~N 으로 되돌린다", () => {
    const draft = toDraft(saved);
    expect(draft.theaterIds).toEqual([4, 18]);
    expect(toPreferences(moveTheater(draft, 1, 0))).toEqual({
      preferredTheaters: [
        { rank: 1, theaterId: 18 },
        { rank: 2, theaterId: 4 },
      ],
      preferredTimeSlot: "EVENING",
      preferredGenres: ["드라마", "모험"],
    });
  });

  it("극장은 최대 3곳 — 넘치면 무시, 다시 누르면 해제", () => {
    let draft = toDraft(saved);
    draft = toggleTheater(draft, 16);
    expect(draft.theaterIds).toEqual([4, 18, 16]);
    expect(isTheaterLimitReached(draft)).toBe(true);
    expect(toggleTheater(draft, 118)).toBe(draft);
    expect(toggleTheater(draft, 18).theaterIds).toEqual([4, 16]);
  });

  it("순위 이동은 범위 밖·같은 자리면 그대로", () => {
    const draft = toDraft(saved);
    expect(moveTheater(draft, 0, 0)).toBe(draft);
    expect(moveTheater(draft, 0, 5)).toBe(draft);
    expect(moveTheater(draft, 0, 1).theaterIds).toEqual([18, 4]);
  });

  it("시간대는 하나만, 같은 칩을 다시 누르면 해제", () => {
    const draft = toDraft(saved);
    expect(setTimeSlot(draft, "NIGHT").timeSlot).toBe("NIGHT");
    expect(setTimeSlot(draft, "EVENING").timeSlot).toBeNull();
  });

  it("장르 토글과 변경 감지(장르 순서 무시)", () => {
    const draft = toDraft(saved);
    expect(toggleGenre(draft, "액션").genres).toEqual(["드라마", "모험", "액션"]);
    expect(toggleGenre(draft, "모험").genres).toEqual(["드라마"]);
    expect(isSameDraft(draft, { ...draft, genres: ["모험", "드라마"] })).toBe(true);
    expect(isSameDraft(draft, toggleGenre(draft, "액션"))).toBe(false);
    expect(isSameDraft(draft, moveTheater(draft, 0, 1))).toBe(false);
  });
});
