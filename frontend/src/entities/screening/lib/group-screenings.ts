import type { Screening, ScreeningAuditorium, ScreeningMovie } from "../model/types";

export interface AuditoriumGroup {
  auditorium: ScreeningAuditorium;
  filmFormat: string | null;
  totalSeats: number;
  screenings: Screening[];
}

export interface MovieGroup {
  movie: ScreeningMovie;
  auditoriums: AuditoriumGroup[];
}

/**
 * W3: 영화별 → (상영관, 상영 형식)별로 묶는다. 입력이 시간순이면 각 묶음도 첫 회차 시각 순서가 된다.
 * 같은 상영관에 2D·3D 가 섞이면 형식별로 따로 묶여 버튼 옆 형식 표기가 틀리지 않는다.
 */
export function groupScreenings(items: readonly Screening[]): MovieGroup[] {
  const movieGroups = new Map<number, MovieGroup>();
  const auditoriumGroups = new Map<string, AuditoriumGroup>();

  for (const screening of items) {
    let movieGroup = movieGroups.get(screening.movie.movieId);
    if (!movieGroup) {
      movieGroup = { movie: screening.movie, auditoriums: [] };
      movieGroups.set(screening.movie.movieId, movieGroup);
    }
    const key = `${screening.movie.movieId}:${screening.auditorium.auditoriumId}:${screening.filmFormat ?? ""}`;
    let auditoriumGroup = auditoriumGroups.get(key);
    if (!auditoriumGroup) {
      auditoriumGroup = {
        auditorium: screening.auditorium,
        filmFormat: screening.filmFormat,
        totalSeats: screening.totalSeats,
        screenings: [],
      };
      auditoriumGroups.set(key, auditoriumGroup);
      movieGroup.auditoriums.push(auditoriumGroup);
    }
    auditoriumGroup.screenings.push(screening);
  }

  return [...movieGroups.values()];
}

/** 그날 그 극장에서 상영하는 영화 목록 (영화 선택 드롭다운) */
export function moviesIn(items: readonly Screening[]): ScreeningMovie[] {
  const seen = new Map<number, ScreeningMovie>();
  for (const screening of items) {
    if (!seen.has(screening.movie.movieId)) seen.set(screening.movie.movieId, screening.movie);
  }
  return [...seen.values()];
}
