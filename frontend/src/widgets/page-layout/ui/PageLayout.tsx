import { Outlet } from "react-router";
import { Footer } from "./Footer";
import { Header } from "./Header";
import styles from "./PageLayout.module.css";

/** 헤더 + 본문 + 푸터. 라우터의 레이아웃 라우트로 쓴다 */
export function PageLayout() {
  return (
    <div className={styles.shell}>
      <Header />
      <main className={`container ${styles.main}`} id="main">
        <Outlet />
      </main>
      <Footer />
    </div>
  );
}
