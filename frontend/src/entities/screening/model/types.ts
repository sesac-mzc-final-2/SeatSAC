import type { ViewGrade } from "@/entities/movie/@x/screening";

/** contracts/openapi/catalog.openapi.yaml — Screening */
export type ScreenType = "NORMAL" | "RECLINER";
/** Booking이 300ms 안에 답하지 않으면 TOTAL (remainingSeats = totalSeats) */
export type RemainingSeatsSource = "TOTAL" | "BOOKING";

export interface ScreeningMovie {
  movieId: number;
  title: string;
  viewGrade: ViewGrade;
}

export interface ScreeningTheater {
  theaterId: number;
  name: string;
}

export interface ScreeningAuditorium {
  auditoriumId: number;
  name: string;
  screenType: ScreenType;
}

export interface Screening {
  screeningId: number;
  movie: ScreeningMovie;
  theater: ScreeningTheater;
  auditorium: ScreeningAuditorium;
  layoutVersion: number;
  /** KST 영업일 YYYY-MM-DD */
  businessDate: string;
  /** ISO-8601 +09:00 */
  startsAt: string;
  endsAt: string;
  filmFormat: string | null;
  totalSeats: number;
  remainingSeats: number;
  remainingSeatsSource: RemainingSeatsSource;
}
