/** 화면 경로 (2.2.5 화면 목록). 다른 담당 화면(W4 좌석 선택 `/screenings/:id`)은 링크 대상으로만 둔다. */
export type ShowtimesQuery = {
  theaterId?: number | string | null;
  date?: string | null;
  movieId?: number | string | null;
};

export const routePatterns = {
  home: "/",
  movie: "/movies/:movieId",
  showtimes: "/showtimes",
  screening: "/screenings/:screeningId",
  bookingPay: "/bookings/:bookingId/pay",
  credits: "/credits",
  callback: "/callback",
  myPage: "/me",
  /** 관리자 화면 (6.3.5). `/ops` 가 아니다 — CloudFront `/ops/*` 는 ops-api(API)로 간다 */
  admin: "/admin",
  adminServices: "/admin/services",
  adminReports: "/admin/reports",
  adminAiDemand: "/admin/ai-demand",
  adminCoupons: "/admin/coupons",
} as const;

export const routes = {
  home: () => "/",
  movie: (movieId: number | string) => `/movies/${movieId}`,
  showtimes: (query: ShowtimesQuery = {}) => {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && value !== "") params.set(key, String(value));
    }
    const qs = params.toString();
    return qs ? `/showtimes?${qs}` : "/showtimes";
  },
  /** W4 좌석 선택 (남궁일, BKG-09) */
  screening: (screeningId: number | string) => `/screenings/${screeningId}`,
  /** W5·W6 결제·결제 결과 (남궁일, BKG-10) */
  bookingPay: (bookingId: number | string) => `/bookings/${bookingId}/pay`,
  credits: () => "/credits",
  callback: () => "/callback",
  /** W8 마이페이지 (3.3.6). 탭은 `?tab=preferences`·`?tab=coupons`(6.3.6) (기본은 예매 내역) */
  myPage: (tab?: "preferences" | "coupons") => (tab ? `/me?tab=${tab}` : "/me"),
  /** 관리자 대시보드 (토큰 `cognito:groups` ∋ ADMIN 일 때만 메뉴, 허용은 ops-api가 판단) */
  admin: () => "/admin",
  /** OPS-20·21 서비스 상태·정지/가동 (6.2.6) */
  adminServices: () => "/admin/services",
  /** OPS-22 오류 리포트 (6.3.7) */
  adminReports: () => "/admin/reports",
  /** OPS-23 AI 범위 밖 질문 (6.3.7) */
  adminAiDemand: () => "/admin/ai-demand",
  /** OPS-24 쿠폰 이벤트 생성 */
  adminCoupons: () => "/admin/coupons",
} as const;
