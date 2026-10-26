import { http } from "@/shared/api";
import type { Screening } from "../model/types";

export interface ListScreeningsParams {
  /** KST 영업일 YYYY-MM-DD */
  date: string;
  theaterId: number;
  movieId?: number | null;
}

/** CAT-03 상영 회차 목록 `{ items }` (시간순, 오늘은 시작한 회차 제외) */
export const listScreenings = (params: ListScreeningsParams, signal?: AbortSignal) =>
  http<{ items: Screening[] }>("/screenings", {
    query: { date: params.date, theaterId: params.theaterId, movieId: params.movieId ?? undefined },
    signal,
    auth: false,
  });

/** CAT-03 회차 단건 (좌석 선택 화면 새로고침·직접 진입) */
export const getScreening = (screeningId: number, signal?: AbortSignal) =>
  http<Screening>(`/screenings/${screeningId}`, { signal, auth: false });
