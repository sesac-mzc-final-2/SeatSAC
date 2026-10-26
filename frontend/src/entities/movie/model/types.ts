/** contracts/openapi/catalog.openapi.yaml — MovieSummary · MovieDetail · MoviePage */
export type ViewGrade = "ALL" | "12" | "15" | "19";
export type MovieStatus = "NOW_SHOWING" | "COMING_SOON";

export interface MovieSummary {
  movieId: number;
  title: string;
  titleEn: string | null;
  viewGrade: ViewGrade;
  /** 시작~종료 간격 최빈값. 러닝타임이 아니라 화면에 표시하지 않는다 (W-2) */
  slotMinutes: number;
  /** TMDB 포스터 URL. 없으면 null → 제목 카드 */
  posterUrl: string | null;
}

export interface MovieDetail extends MovieSummary {
  synopsis: string | null;
  director: string | null;
  cast: string[];
  releaseDate: string | null;
  genres: string[];
  /** TMDB 원 러닝타임. null이면 그 줄을 숨긴다 (slotMinutes로 대체하지 않음, W-2) */
  runtimeMinutes: number | null;
}

export interface MoviePage {
  content: MovieSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
