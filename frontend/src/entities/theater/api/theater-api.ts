import { http } from "@/shared/api";
import type { Theater } from "../model/types";

export interface ListTheatersParams {
  hasLayout?: boolean;
  region?: string;
}

/** CAT-03 극장 목록 `{ items }` (상영시간 화면의 극장 선택용) */
export const listTheaters = (params: ListTheatersParams = {}, signal?: AbortSignal) =>
  http<{ items: Theater[] }>("/theaters", {
    query: { hasLayout: params.hasLayout, region: params.region },
    signal,
    auth: false,
  });
