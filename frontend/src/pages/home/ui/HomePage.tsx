import { Link } from "react-router";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import { HomeHero } from "@/widgets/home-hero";
import { MovieCatalog } from "@/widgets/movie-catalog";
import styles from "./HomePage.module.css";

/** W1 메인 — 서비스 소개 히어로 · 상영시간표 바로가기 줄 · 상영중 영화 (CAT-01) */
export function HomePage() {
  useDocumentTitle("상영중인 영화");
  return (
    <div className={styles.page}>
      <HomeHero />

      <section className={styles.banner} aria-label="상영시간표 바로가기">
        <div className={styles.bannerIcon}>
          <Icon name="calendar" size={22} strokeWidth={1.5} />
        </div>
        <div className={styles.bannerCopy}>
          <h2>지금 예매해 보세요</h2>
          <p>
            극장과 날짜를 고르면<span> 회차별</span> 남은 좌석을 볼 수 있어요.
          </p>
        </div>
        <Link to={routes.showtimes()} className={styles.bannerAction}>
          상영시간표 보기 <Icon name="arrow-right" size={17} />
        </Link>
      </section>

      <MovieCatalog />

      <div className={styles.note}>
        <span>
          <Icon name="armchair" size={16} />
        </span>
        <p>
          오직 영화에 집중할 수 있도록.<span> SeatSAC이 당신의 자리를 준비합니다.</span>
        </p>
        <span className={styles.noteTag}>비영리 교육용 시연</span>
      </div>
    </div>
  );
}
