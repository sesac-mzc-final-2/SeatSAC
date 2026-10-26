import { Link } from "react-router";
import { routes } from "@/shared/config";
import styles from "./Footer.module.css";

export function Footer() {
  return (
    <footer className={styles.footer}>
      <div className={`container ${styles.inner}`}>
        <span>SeatSAC · 비영리 교육용 프로젝트 · 실제 예매·결제는 이루어지지 않습니다</span>
        <nav className={styles.links} aria-label="바닥글 메뉴">
          <Link to={routes.credits()}>출처·크레딧</Link>
        </nav>
      </div>
    </footer>
  );
}
