import { Link, NavLink } from "react-router";
import { SessionControls } from "@/features/login";
import { isAdminUser, useSession } from "@/entities/session";
import { routes } from "@/shared/config";
import { Logo } from "./Logo";
import styles from "./Header.module.css";

/** 공통 헤더 (2.2.5 경계: 로그인 상태·메뉴는 모든 화면이 같은 컴포넌트를 쓴다). page-layout 의 일부 */
export function Header() {
  // 로그인하면 계정 메뉴가 길어져 아주 좁은 폭에서 두 줄이 된다 → 그 폭에서는 주 메뉴를 숨긴다 (Header.module.css)
  const session = useSession();
  const authed = session.status === "authenticated";
  // 토큰 cognito:groups 에 ADMIN 이 있을 때만 (표시용 — 허용은 ops-api 가 정한다, ADR-119)
  const admin = authed && isAdminUser(session.user);
  return (
    <header className={styles.header} data-authed={authed || undefined}>
      <div className={`container ${styles.inner}`}>
        <Link to={routes.home()} aria-label="SeatSAC 홈">
          <Logo />
        </Link>
        <nav className={styles.nav} aria-label="주 메뉴">
          <NavLink to={routes.home()} end className={styles.navLink}>
            영화
          </NavLink>
          <NavLink to={routes.showtimes()} className={styles.navLink}>
            상영시간표
          </NavLink>
          {admin && (
            <NavLink to={routes.admin()} className={styles.navLink}>
              관리자
            </NavLink>
          )}
        </nav>
        <div className={styles.actions}>
          <SessionControls />
        </div>
      </div>
    </header>
  );
}
