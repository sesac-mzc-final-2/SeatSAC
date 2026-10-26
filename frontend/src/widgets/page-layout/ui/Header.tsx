import { Link, NavLink } from "react-router";
import { SessionControls } from "@/features/login";
import { routes } from "@/shared/config";
import styles from "./Header.module.css";

/** 공통 헤더 (2.2.5 경계: 로그인 상태·메뉴는 모든 화면이 같은 컴포넌트를 쓴다). page-layout 의 일부 */
export function Header() {
  return (
    <header className={styles.header}>
      <div className={`container ${styles.inner}`}>
        <Link to={routes.home()} className={styles.logo} aria-label="싯싹 홈">
          싯싹
        </Link>
        <nav className={styles.nav} aria-label="주 메뉴">
          <NavLink to={routes.home()} end className={styles.navLink}>
            영화
          </NavLink>
          <NavLink to={routes.showtimes()} className={styles.navLink}>
            상영시간
          </NavLink>
        </nav>
        <SessionControls />
      </div>
    </header>
  );
}
