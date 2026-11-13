import { Link } from "react-router";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import { HomeHero } from "@/widgets/home-hero";
import { MovieCatalog } from "@/widgets/movie-catalog";
import styles from "./HomePage.module.css";

/** W1 메인 — 영화 히어로 · 서비스 소개 줄(상영시간표 바로가기) · 상영중 영화 (CAT-01) */
export function HomePage() {
  useDocumentTitle("상영중인 영화");
  return (
    <div className={styles.page}>
      <HomeHero />

      <section className={styles.banner} aria-label="서비스 소개">
        <div className={styles.bannerIcon}>
          <Icon name="armchair" size={24} strokeWidth={1.5} />
        </div>
        <div className={styles.bannerCopy}>
          <h2>실시간 좌석 예매</h2>
          <p>
            좌석을 고르면 5분간 내 자리로 잡힙니다.<span> 다른 사람 화면에도 1초 안에 반영돼요.</span>
          </p>
        </div>
        <div className={styles.miniSeats} aria-hidden="true">
          {Array.from({ length: 12 }, (_, i) => (
            <span key={i} className={i === 5 || i === 6 ? styles.held : undefined} />
          ))}
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
