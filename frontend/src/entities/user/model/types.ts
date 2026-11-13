/** contracts/openapi/user.openapi.yaml — Profile · MyBooking · MyBookingPage · Preferences */
export interface Profile {
  /** Cognito sub (= X-User-Id) */
  userId: string;
  email: string;
  name: string | null;
  // 계약의 role(USER·ADMIN)은 받지만 쓰지 않는다 — 관리자 화면·기능이 없다
  createdAt: string;
}

/** 마이페이지 내역은 PENDING 을 뺀 네 상태만 온다 (USR-04). 배지 4종 = 예매 완료 · 결제 실패 · 시간 초과 · 취소됨 */
export type MyBookingStatus = "CONFIRMED" | "PAYMENT_FAILED" | "EXPIRED" | "CANCELED";

export interface MyBookingScreening {
  screeningId: number;
  movieTitle: string;
  theaterName: string;
  auditoriumName: string;
  /** ISO-8601 +09:00 */
  startsAt: string;
  endsAt: string;
}

export interface MyBooking {
  bookingId: number;
  status: MyBookingStatus;
  seatLabels: string[];
  seatCount?: number;
  /** 원 */
  amount: number;
  createdAt: string;
  confirmedAt: string | null;
  canceledAt: string | null;
  /** Catalog 가 답하지 않으면 null → 「상영 정보를 불러오지 못했어요」 한 줄 */
  screening: MyBookingScreening | null;
}

export interface MyBookingPage {
  content: MyBooking[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** MORNING ~12 · AFTERNOON 12~17 · EVENING 17~21 · NIGHT 21~ */
export type TimeSlot = "MORNING" | "AFTERNOON" | "EVENING" | "NIGHT";

export interface PreferredTheater {
  /** 1~3, 중복 없음 */
  rank: number;
  theaterId: number;
}

export interface Preferences {
  /** 최대 3곳 */
  preferredTheaters: PreferredTheater[];
  /** 하나만 (없으면 null) */
  preferredTimeSlot: TimeSlot | null;
  /** 저장만 한다 (D6) */
  preferredGenres: string[];
}
