/** contracts/openapi/user.openapi.yaml — Profile · MyBooking · MyBookingPage · Preferences */
export interface Profile {
  /** Cognito sub (= X-User-Id) */
  userId: string;
  email: string;
  name: string | null;
  // 계약의 role(USER·ADMIN)은 받지만 쓰지 않는다 — 관리자 화면·기능이 없다
  createdAt: string;
  /**
   * 회원 등급(MSA 6.3.1, ADR-118) — Gateway 가 토큰 `cognito:groups` 로 정한 값 그대로(저장 안 함). 열린 집합이고,
   * 필드가 없던 이전 응답·모르는 값은 NORMAL 로 본다. VIP 지정은 다시 로그인해야 반영된다
   */
  grade?: UserGrade;
}

export type UserGrade = "NORMAL" | "VIP" | (string & {});

/** 마이페이지 내역은 PENDING 을 뺀 네 상태만 온다 (USR-04). 배지 4종 = 예매 완료 · 결제 실패 · 시간 초과 · 취소됨 */
export type MyBookingStatus = "CONFIRMED" | "PAYMENT_FAILED" | "EXPIRED" | "CANCELED";

export interface MyBookingScreening {
  screeningId: number;
  /** catalog 회차 요약이 아직 주지 않으면 null (11/17 추가 필드) */
  movieId: number | null;
  movieTitle: string;
  /** TMDB 포스터(CAT-01 posterUrl 과 같은 값). 없으면 null → 제목 카드 */
  posterUrl: string | null;
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
