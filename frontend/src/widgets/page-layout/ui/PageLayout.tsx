import { Outlet, ScrollRestoration, useLocation } from "react-router";
import { Footer } from "./Footer";
import { Header } from "./Header";
import styles from "./PageLayout.module.css";

/**
 * 헤더 + 본문 + 푸터. 라우터의 레이아웃 라우트로 쓴다.
 * 스크롤: 새 화면으로 가면 맨 위, 뒤로 가기는 보던 위치 복원. 키를 pathname 으로 둬서
 * 상영시간표 필터(쿼리만 바뀜)를 바꿀 때는 위치를 유지한다
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
      <ScrollRestoration getKey={(location) => location.pathname} />
    </div>
  );
}
