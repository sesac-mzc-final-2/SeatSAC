import { queryOptions } from "@tanstack/react-query";
import { listTheaters, type ListTheatersParams } from "./theater-api";

export const theaterKeys = {
  all: ["theaters"] as const,
  list: (params: ListTheatersParams) => [...theaterKeys.all, "list", params] as const,
};

export const theaterListQuery = (params: ListTheatersParams = { hasLayout: true }) =>
  queryOptions({
    queryKey: theaterKeys.list(params),
    queryFn: ({ signal }) => listTheaters(params, signal),
    staleTime: 10 * 60 * 1000,
  });
