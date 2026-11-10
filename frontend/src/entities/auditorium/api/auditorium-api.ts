import { http } from "@/shared/api";
import type { AuditoriumLayout } from "../model/types";

/**
 * CAT-04 상영관 좌석 배치도. 좌석 점유 상태는 들어 있지 않다(BKG-01이 따로 준다).
 *
 * @param version 회차의 `layoutVersion`. 생략하면 최신 배치가 와서 회차의 좌석과 어긋날 수 있다.
 */
export const getAuditoriumLayout = (auditoriumId: number, version?: number | null, signal?: AbortSignal) =>
  http<AuditoriumLayout>(`/auditoriums/${auditoriumId}/layout`, {
    query: { version: version ?? undefined },
    signal,
    auth: false,
  });
