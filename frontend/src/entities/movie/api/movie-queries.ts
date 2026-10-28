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
