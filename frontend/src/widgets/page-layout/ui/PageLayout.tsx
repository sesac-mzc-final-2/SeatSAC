import { Outlet, ScrollRestoration, useLocation } from "react-router";
import { Footer } from "./Footer";
import { Header } from "./Header";
import styles from "./PageLayout.module.css";

/**
 * 헤더 + 본문 + 푸터. 라우터의 레이아웃 라우트로 쓴다.
 * 스크롤: 새로 방문한 화면은 맨 위, 뒤로 가기는 보던 위치 복원(기본 키 = 방문마다 다른 location.key).
 * 상영시간표 필터처럼 같은 화면 안의 쿼리 변경은 그 쪽에서 preventScrollReset 으로 위치를 유지한다
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
      <ScrollRestoration />
    </div>
  );
}
