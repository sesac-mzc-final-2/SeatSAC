import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";
import { getMyPreferences, getMyProfile, listMyBookings, type ListMyBookingsParams } from "./user-api";

type BookingsKeyParams = Pick<ListMyBookingsParams, "status" | "size">;

/** 로그아웃·다른 계정 로그인 때 `userKeys.all` 로 전부 지운다 */
export const userKeys = {
  all: ["me"] as const,
  profile: () => [...userKeys.all, "profile"] as const,
  bookings: (params: BookingsKeyParams) => [...userKeys.all, "bookings", params] as const,
  preferences: () => [...userKeys.all, "preferences"] as const,
};

export const myProfileQuery = () =>
  queryOptions({
    queryKey: userKeys.profile(),
    queryFn: ({ signal }) => getMyProfile(signal),
    staleTime: 5 * 60 * 1000,
  });

/** W8 예매 내역: page 0 부터 「더 보기」 */
export const myBookingsQuery = (params: BookingsKeyParams = {}) =>
  infiniteQueryOptions({
    queryKey: userKeys.bookings(params),
    queryFn: ({ pageParam, signal }) => listMyBookings({ ...params, page: pageParam }, signal),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => (lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined),
  });

export const myPreferencesQuery = () =>
  queryOptions({
    queryKey: userKeys.preferences(),
    queryFn: ({ signal }) => getMyPreferences(signal),
  });
