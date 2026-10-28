import { Link } from "react-router";
import { routes } from "@/shared/config";
import { Icon } from "@/shared/ui";
import { Logo } from "./Logo";
import styles from "./Footer.module.css";

export function Footer() {
  return (
    <footer className={styles.footer}>
      <div className="container">
        <div className={styles.rule}>
          <div className={styles.top}>
            <Link to={routes.home()} aria-label="SeatSAC 홈">
              <Logo small />
            </Link>
            <nav className={styles.links} aria-label="바닥글 메뉴">
              <span className={styles.tagline}>영화의 시작, 나의 자리.</span>
              <Link to={routes.credits()} className={styles.credits}>
                출처·크레딧 <Icon name="chevron-right" size={13} />
              </Link>
            </nav>
          </div>
          <div className={styles.bottom}>
            <p>SeatSAC은 비영리 교육용 시연 서비스입니다. 실제 예매·결제가 이루어지지 않습니다.</p>
            <span>© 2026 SeatSAC</span>
          </div>
        </div>
      </div>
    </footer>
  );
}
