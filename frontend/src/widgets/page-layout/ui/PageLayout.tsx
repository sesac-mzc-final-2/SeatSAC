import { Outlet, ScrollRestoration, useLocation } from "react-router";
import { routePatterns } from "@/shared/config";
import { Footer } from "./Footer";
import { Header } from "./Header";
import styles from "./PageLayout.module.css";

/**
 * 헤더 + 본문 + 푸터. 라우터의 레이아웃 라우트로 쓴다.
 * 스크롤: 새 화면으로 가면 맨 위, 뒤로 가기는 보던 위치 복원(기본 키 = 탐색마다 다른 location.key).
 * 상영시간표만 pathname 을 키로 써서 필터(쿼리만 바뀜)를 바꿀 때 위치를 유지한다
 */
export function PageLayout() {
  const { pathname } = useLocation();
  return (
    <div className={styles.shell}>
      <Header />
      <main className={`container ${styles.main}`} id="main" key={pathname}>
        <Outlet />
      </main>
      <Footer />
      <ScrollRestoration
        getKey={(location) => (location.pathname === routePatterns.showtimes ? location.pathname : location.key)}
      />
    </div>
  );
}
