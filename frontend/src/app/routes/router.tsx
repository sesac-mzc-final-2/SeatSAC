import { createBrowserRouter, type RouteObject } from "react-router";
import { AuthCallbackPage } from "@/pages/auth-callback";
import { BookingPayPage } from "@/pages/booking-pay";
import { CreditsPage } from "@/pages/credits";
import { HomePage } from "@/pages/home";
import { MovieDetailPage } from "@/pages/movie-detail";
import { NotFoundPage } from "@/pages/not-found";
import { ScreeningPage } from "@/pages/screening";
import { ShowtimesPage } from "@/pages/showtimes";
import { routePatterns } from "@/shared/config";
import { PageLayout } from "@/widgets/page-layout";
import { RouteErrorPage } from "./route-error-page";

/** 각 화면의 오류는 레이아웃(헤더·푸터) 안에서 보여 준다 */
const page = (route: RouteObject): RouteObject => ({ ...route, errorElement: <RouteErrorPage /> });

/**
 * 2.2.5 화면 목록. W8 `/me`·W9 `/admin/ai`는 각 WBS에서 추가한다.
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
      page({ path: "*", element: <NotFoundPage /> }),
    ],
  },
]);
