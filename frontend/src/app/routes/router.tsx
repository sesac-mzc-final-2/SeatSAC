import { createBrowserRouter, type RouteObject } from "react-router";
import { AdminOverviewPage, AdminSectionPage } from "@/pages/admin";
import { AuthCallbackPage } from "@/pages/auth-callback";
import { BookingPayPage } from "@/pages/booking-pay";
import { CreditsPage } from "@/pages/credits";
import { HomePage } from "@/pages/home";
import { MovieDetailPage } from "@/pages/movie-detail";
import { MyPage } from "@/pages/my-page";
import { NotFoundPage } from "@/pages/not-found";
import { ScreeningPage } from "@/pages/screening";
import { ShowtimesPage } from "@/pages/showtimes";
import { routePatterns } from "@/shared/config";
import { AdminLayout } from "@/widgets/admin-layout";
import { PageLayout } from "@/widgets/page-layout";
import { RouteErrorPage } from "./route-error-page";

/** 각 화면의 오류는 레이아웃(헤더·푸터) 안에서 보여 준다 */
const page = (route: RouteObject): RouteObject => ({ ...route, errorElement: <RouteErrorPage /> });

/**
 * 2.2.5 화면 목록. AI 챗봇(3.3.7)은 별도 경로 없이 레이아웃 우하단 대화창이다(`features/ai-chat`, ADR-82).
 * 관리자 대시보드(6.3.5, ADR-119)는 `/admin/*` — `/ops/*` 는 CloudFront 가 ops-api(API)로 보내므로 화면 경로로 쓰지 않는다.
 * `/callback`은 CloudFront가 쿼리를 무시하고 index.html을 주므로 SPA 라우트로 받는다.
 */
export const router = createBrowserRouter([
  {
    element: <PageLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      page({ path: routePatterns.home, element: <HomePage /> }),
      page({ path: routePatterns.movie, element: <MovieDetailPage /> }),
      page({ path: routePatterns.showtimes, element: <ShowtimesPage /> }),
      page({ path: routePatterns.screening, element: <ScreeningPage /> }),
      page({ path: routePatterns.bookingPay, element: <BookingPayPage /> }),
      page({ path: routePatterns.credits, element: <CreditsPage /> }),
      page({ path: routePatterns.callback, element: <AuthCallbackPage /> }),
      page({ path: routePatterns.myPage, element: <MyPage /> }),
      page({
        path: routePatterns.admin,
        element: <AdminLayout />,
        children: [
          { index: true, element: <AdminOverviewPage /> },
          {
            path: routePatterns.adminServices,
            element: (
              <AdminSectionPage
                icon="shield-check"
                title="서비스 상태"
                description="서비스별 신호등·HPA·노드 CPU와 정지/가동 버튼이 들어올 자리예요. 준비 중이에요."
              />
            ),
          },
          {
            path: routePatterns.adminReports,
            element: (
              <AdminSectionPage
                icon="alert-circle"
                title="오류 리포트"
                description="날짜를 골라 일자별 오류·보안 리포트(CSV)를 내려받는 자리예요. 준비 중이에요."
              />
            ),
          },
          {
            path: routePatterns.adminAiDemand,
            element: (
              <AdminSectionPage
                icon="message-square"
                title="AI 범위 밖 질문"
                description="챗봇이 답하지 못한 질문의 분류별 그래프가 들어올 자리예요. 준비 중이에요."
              />
            ),
          },
          {
            path: routePatterns.adminCoupons,
            element: (
              <AdminSectionPage
                icon="ticket"
                title="쿠폰 이벤트"
                description="VIP 선착순 쿠폰 이벤트를 만들고 목록을 보는 자리예요. 준비 중이에요."
              />
            ),
          },
        ],
      }),
      page({ path: "*", element: <NotFoundPage /> }),
    ],
  },
]);
