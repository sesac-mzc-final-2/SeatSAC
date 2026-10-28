import { http } from "@/shared/api";
import type { MovieDetail, MoviePage, MovieStatus } from "../model/types";

export interface ListMoviesParams {
  status?: MovieStatus;
  page?: number;
  size?: number;
}

/** CAT-01 영화 목록 (페이징) */
export const listMovies = (params: ListMoviesParams, signal?: AbortSignal) =>
  http<MoviePage>("/movies", {
    query: { status: params.status, page: params.page, size: params.size },
    signal,
    auth: false,
  });

/** CAT-02 영화 상세 (TMDB 보충, 없는 항목은 null·[]) */
export const getMovie = (movieId: number, signal?: AbortSignal) =>
  http<MovieDetail>(`/movies/${movieId}`, { signal, auth: false });
