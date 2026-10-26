import { describe, expect, it } from "vitest";
import type { Screening } from "../model/types";
import { groupScreenings, moviesIn } from "./group-screenings";

const make = (id: number, movieId: number, auditoriumId: number, startsAt: string): Screening => ({
  screeningId: id,
  movie: { movieId, title: `영화 ${movieId}`, viewGrade: "12" },
  theater: { theaterId: 4, name: "건대입구" },
  auditorium: { auditoriumId, name: `${auditoriumId}관`, screenType: "NORMAL" },
  layoutVersion: 1,
  businessDate: "2026-11-16",
  startsAt,
  endsAt: startsAt,
  filmFormat: "2D",
  totalSeats: 100,
  remainingSeats: 90,
  remainingSeatsSource: "BOOKING",
});

describe("groupScreenings", () => {
  it("영화별 → 상영관별로 묶고 입력 순서를 지킨다", () => {
    const items = [
      make(1, 10, 2, "2026-11-16T13:40:00+09:00"),
      make(2, 20, 3, "2026-11-16T14:30:00+09:00"),
      make(3, 10, 6, "2026-11-16T15:00:00+09:00"),
      make(4, 10, 2, "2026-11-16T16:20:00+09:00"),
    ];
    const groups = groupScreenings(items);
    expect(groups.map((g) => g.movie.movieId)).toEqual([10, 20]);
    expect(groups[0].auditoriums.map((a) => a.auditorium.auditoriumId)).toEqual([2, 6]);
    expect(groups[0].auditoriums[0].screenings.map((s) => s.screeningId)).toEqual([1, 4]);
    expect(groups[1].auditoriums[0].screenings.map((s) => s.screeningId)).toEqual([2]);
  });

  it("moviesIn은 중복 없이 첫 등장 순서", () => {
    const items = [make(1, 10, 2, ""), make(2, 20, 3, ""), make(3, 10, 6, "")];
    expect(moviesIn(items).map((m) => m.movieId)).toEqual([10, 20]);
  });

  it("빈 입력", () => {
    expect(groupScreenings([])).toEqual([]);
  });
});
