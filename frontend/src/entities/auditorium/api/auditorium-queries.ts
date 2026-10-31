import { queryOptions } from "@tanstack/react-query";
import { getAuditoriumLayout } from "./auditorium-api";

export const auditoriumKeys = {
  all: ["auditoriums"] as const,
  layout: (auditoriumId: number, version?: number | null) =>
    [...auditoriumKeys.all, "layout", auditoriumId, version ?? "latest"] as const,
};

/**
 * (상영관, 배치 버전)이 정해지면 좌석 구성은 바뀌지 않는다 → 버전을 주고 받으면 오래 캐시한다.
 * 버전을 생략하면 최신이 바뀔 수 있으니 catalog 캐시와 같은 10분만 둔다.
 */
export const auditoriumLayoutQuery = (auditoriumId: number, version?: number | null) =>
  queryOptions({
    queryKey: auditoriumKeys.layout(auditoriumId, version),
    queryFn: ({ signal }) => getAuditoriumLayout(auditoriumId, version, signal),
    staleTime: version == null ? 10 * 60 * 1000 : Infinity,
  });
