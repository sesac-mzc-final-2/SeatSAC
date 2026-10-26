import { useCallback } from "react";
import { useSearchParams } from "react-router";
import { isValidIsoDate, todayKst } from "@/shared/lib";

export interface ShowtimesFilter {
  theaterId: number | null;
  /** KST 영업일 YYYY-MM-DD (기본 오늘) */
  date: string;
  movieId: number | null;
}

function parseId(value: string | null): number | null {
  if (value === null || value === "") return null;
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : null;
}

/**
 * W3 필터 상태는 URL(`/showtimes?theaterId&date&movieId`)이 원본이다.
 * 새로고침·공유·W2 「예매하기」(movieId만 넘김)가 모두 같은 주소로 들어온다.
 */
export function useShowtimesFilter(defaultTheaterId: number | null) {
  const [params, setParams] = useSearchParams();

  const rawDate = params.get("date");
  const filter: ShowtimesFilter = {
    theaterId: parseId(params.get("theaterId")) ?? defaultTheaterId,
    date: isValidIsoDate(rawDate) ? rawDate : todayKst(),
    movieId: parseId(params.get("movieId")),
  };

  const update = useCallback(
    (patch: Partial<ShowtimesFilter>) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          for (const [key, value] of Object.entries(patch)) {
            if (value === null || value === undefined || value === "") next.delete(key);
            else next.set(key, String(value));
          }
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  return {
    filter,
    setTheater: (theaterId: number) => update({ theaterId }),
    setDate: (date: string) => update({ date }),
    setMovie: (movieId: number | null) => update({ movieId }),
  };
}
