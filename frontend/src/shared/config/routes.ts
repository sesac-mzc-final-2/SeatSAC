/** 화면 경로 (2.2.5 화면 목록). 다른 담당 화면(W4 좌석 선택 `/screenings/:id`, W8 `/me`)도 링크 대상으로만 둔다. */
export type ShowtimesQuery = {
  theaterId?: number | string | null;
  date?: string | null;
  movieId?: number | string | null;
};

export const routePatterns = {
  home: "/",
  movie: "/movies/:movieId",
  showtimes: "/showtimes",
  screening: "/screenings/:screeningId",
  credits: "/credits",
  callback: "/callback",
  myPage: "/me",
} as const;

export const routes = {
  home: () => "/",
  movie: (movieId: number | string) => `/movies/${movieId}`,
  showtimes: (query: ShowtimesQuery = {}) => {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
    }
    const qs = params.toString();
    return qs ? `/showtimes?${qs}` : "/showtimes";
  },
  /** W4 좌석 선택 (남궁일, BKG-09) */
  screening: (screeningId: number | string) => `/screenings/${screeningId}`,
  credits: () => "/credits",
  callback: () => "/callback",
  /** W8 마이페이지 (3.3.6) */
  myPage: () => "/me",
} as const;
