import { http } from "@/shared/api";
import type { MyBookingPage, MyBookingStatus, Preferences, Profile } from "../model/types";

/** 프로필 — 첫 호출 때 서버가 만든다 (Cognito GetUser 실패는 503 IDENTITY_UNAVAILABLE) */
export const getMyProfile = (signal?: AbortSignal) => http<Profile>("/users/me", { signal });

export const updateMyProfile = (body: { name: string }) => http<Profile>("/users/me", { method: "PATCH", body });

export interface ListMyBookingsParams {
  /** 생략 시 PENDING 제외 전부 */
  status?: MyBookingStatus;
  page?: number;
  size?: number;
}

/** USR-04 예매 내역 (Booking 실패 시 503 BOOKING_UNAVAILABLE) */
export const listMyBookings = (params: ListMyBookingsParams = {}, signal?: AbortSignal) =>
  http<MyBookingPage>("/users/me/bookings", {
    query: { status: params.status, page: params.page ?? 0, size: params.size ?? 20 },
    signal,
  });

/** USR-05 선호 조회 — 없으면 빈 값 200 */
export const getMyPreferences = (signal?: AbortSignal) => http<Preferences>("/users/me/preferences", { signal });

/** USR-05 선호 저장 (전체 교체). 없는 theaterId 는 400 VALIDATION_FAILED */
export const replaceMyPreferences = (body: Preferences) =>
  http<Preferences>("/users/me/preferences", { method: "PUT", body });
