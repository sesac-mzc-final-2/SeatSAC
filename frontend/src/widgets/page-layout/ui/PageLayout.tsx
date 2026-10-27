import { useEffect } from "react";
import { Outlet, useLocation } from "react-router";
import { Footer } from "./Footer";
import { Header } from "./Header";
import styles from "./PageLayout.module.css";

/** 헤더 + 본문 + 푸터. 라우터의 레이아웃 라우트로 쓴다. 화면이 바뀌면 맨 위로 */
export function PageLayout() {
  const { pathname } = useLocation();
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);

  return (
    <div className={styles.shell}>
      <Header />
      <main className={`container ${styles.main}`} id="main" key={pathname}>
        <Outlet />
      </main>
      <Footer />
    </div>
  );
}
