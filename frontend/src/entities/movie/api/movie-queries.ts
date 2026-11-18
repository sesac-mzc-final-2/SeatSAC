import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";
import { getMovie, listMovies, type ListMoviesParams } from "./movie-api";

type ListKeyParams = Pick<ListMoviesParams, "status" | "size">;

export const movieKeys = {
  all: ["movies"] as const,
  list: (params: ListKeyParams) => [...movieKeys.all, "list", params] as const,
  detail: (movieId: number) => [...movieKeys.all, "detail", movieId] as const,
};

/** W1 메인: page 0부터 「더 보기」로 다음 페이지 */
export const movieListQuery = (params: ListKeyParams = {}) =>
  infiniteQueryOptions({
    queryKey: movieKeys.list(params),
    queryFn: ({ pageParam, signal }) => listMovies({ ...params, page: pageParam }, signal),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined),
  });

export const movieDetailQuery = (movieId: number) =>
  queryOptions({
    queryKey: movieKeys.detail(movieId),
    queryFn: ({ signal }) => getMovie(movieId, signal),
  });

/** 지금 상영 중인 영화 한 페이지 전부 (챗봇의 영화 고르기용). catalog `size` 상한이 50이라 50으로 — 상영작은 22편.
 * 메인 목록(movieListQuery)은 infinite 쿼리라 같은 키를 쓰면 캐시 모양이 섞여 키를 따로 둔다 */
export const nowShowingMoviesQuery = () =>
  queryOptions({
    queryKey: [...movieKeys.all, "now-showing-all"] as const,
    queryFn: ({ signal }) => listMovies({ status: "NOW_SHOWING", size: 50 }, signal),
    staleTime: 10 * 60 * 1000,
  });
