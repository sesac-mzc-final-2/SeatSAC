import { queryOptions } from "@tanstack/react-query";
import { getBooking, getSeatSnapshot } from "./booking-api";

export const bookingKeys = {
  all: ["bookings"] as const,
  detail: (bookingId: number) => [...bookingKeys.all, "detail", bookingId] as const,
  seats: (screeningId: number) => ["seat-snapshot", screeningId] as const,
};

/**
 * BKG-01 스냅샷. 실시간 반영은 SSE가 하므로 주기 재조회는 두지 않는다 —
 * 이벤트에 빈틈이 생기거나 연결이 끊겼을 때만 화면이 직접 다시 받는다.
 */
export const seatSnapshotQuery = (screeningId: number) =>
  queryOptions({
    queryKey: bookingKeys.seats(screeningId),
    queryFn: ({ signal }) => getSeatSnapshot(screeningId, signal),
    staleTime: 0,
    gcTime: 60 * 1000,
  });

/**
 * 예매 단건. 결제 결과가 바뀌는 값이라 캐시하지 않는다.
 * (서버가 PENDING + 결제 요청이 있으면 Payment를 조회해 반영한 뒤 응답한다)
 */
export const bookingDetailQuery = (bookingId: number) =>
  queryOptions({
    queryKey: bookingKeys.detail(bookingId),
    queryFn: ({ signal }) => getBooking(bookingId, signal),
    staleTime: 0,
  });
