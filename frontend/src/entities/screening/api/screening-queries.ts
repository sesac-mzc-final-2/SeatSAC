import { queryOptions } from "@tanstack/react-query";
import { getScreening, listScreenings, type ListScreeningsParams } from "./screening-api";

export const screeningKeys = {
  all: ["screenings"] as const,
  list: (params: ListScreeningsParams) => [...screeningKeys.all, "list", params] as const,
  detail: (screeningId: number) => [...screeningKeys.all, "detail", screeningId] as const,
};

/**
 * 잔여석이 들어 있어 오래 캐시하지 않는다. 화면을 열어 둔 동안에도 1분마다(탭이 보일 때만) 다시 받아
 * 이미 시작한 회차·바뀐 잔여석이 남지 않게 하고, 탭으로 돌아오면 바로 갱신한다
 */
export const screeningListQuery = (params: ListScreeningsParams) =>
  queryOptions({
    queryKey: screeningKeys.list(params),
    queryFn: ({ signal }) => listScreenings(params, signal),
    staleTime: 15 * 1000,
    refetchInterval: 60 * 1000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: true,
  });

export const screeningDetailQuery = (screeningId: number) =>
  queryOptions({
    queryKey: screeningKeys.detail(screeningId),
    queryFn: ({ signal }) => getScreening(screeningId, signal),
    staleTime: 15 * 1000,
  });
