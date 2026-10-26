import { queryOptions } from "@tanstack/react-query";
import { getScreening, listScreenings, type ListScreeningsParams } from "./screening-api";

export const screeningKeys = {
  all: ["screenings"] as const,
  list: (params: ListScreeningsParams) => [...screeningKeys.all, "list", params] as const,
  detail: (screeningId: number) => [...screeningKeys.all, "detail", screeningId] as const,
};

/** 잔여석이 들어 있어 오래 캐시하지 않는다 */
export const screeningListQuery = (params: ListScreeningsParams) =>
  queryOptions({
    queryKey: screeningKeys.list(params),
    queryFn: ({ signal }) => listScreenings(params, signal),
    staleTime: 15 * 1000,
  });

export const screeningDetailQuery = (screeningId: number) =>
  queryOptions({
    queryKey: screeningKeys.detail(screeningId),
    queryFn: ({ signal }) => getScreening(screeningId, signal),
    staleTime: 15 * 1000,
  });
