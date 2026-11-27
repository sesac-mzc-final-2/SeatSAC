import { createBrowserRouter, type RouteObject } from "react-router";
import {
  AdminAiDemandPage,
  AdminCouponsPage,
  AdminOverviewPage,
  AdminReportsPage,
  AdminServicesPage,
} from "@/pages/admin";
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
          { path: routePatterns.adminServices, element: <AdminServicesPage /> },
          { path: routePatterns.adminReports, element: <AdminReportsPage /> },
          { path: routePatterns.adminAiDemand, element: <AdminAiDemandPage /> },
          { path: routePatterns.adminCoupons, element: <AdminCouponsPage /> },
        ],
      }),
      page({ path: "*", element: <NotFoundPage /> }),
    ],
  },
]);
