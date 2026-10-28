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
 *
 * @param selectableTheaterIds 선택할 수 있는 극장 ID 목록. 아직 못 받았으면(null) URL 값을 그대로 쓰지 않고 theaterId 를 null 로 둔다.
 *   URL 의 theaterId 가 목록에 없으면 첫 번째 극장으로 대체한다.
 */
export function useShowtimesFilter(selectableTheaterIds: number[] | null) {
  const [params, setParams] = useSearchParams();

  const requestedTheaterId = parseId(params.get("theaterId"));
  let theaterId: number | null = null;
  if (selectableTheaterIds && selectableTheaterIds.length > 0) {
    theaterId =
      requestedTheaterId !== null && selectableTheaterIds.includes(requestedTheaterId)
        ? requestedTheaterId
        : selectableTheaterIds[0];
  }

  // 지난 날짜(끝난 회차)는 고를 수 없으니 오늘로 보정한다 (ISO 날짜는 문자열 비교로 충분)
  const today = todayKst();
  const rawDate = params.get("date");
  const filter: ShowtimesFilter = {
    theaterId,
    date: isValidIsoDate(rawDate) && rawDate >= today ? rawDate : today,
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
