import { Link } from "react-router";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import { HomeHero } from "@/widgets/home-hero";
import { MovieCatalog } from "@/widgets/movie-catalog";
import styles from "./HomePage.module.css";

/** W1 메인 — 히어로 · 실시간 선점 안내 · 상영중 영화 (CAT-01) */
export function HomePage() {
  useDocumentTitle("상영중인 영화");
  return (
    <div className={styles.page}>
      <HomeHero />

      <section className={styles.banner} aria-label="실시간 좌석 선점 안내">
        <div className={styles.bannerIcon}>
          <Icon name="armchair" size={24} strokeWidth={1.5} />
        </div>
        <div className={styles.bannerCopy}>
          <h2>좋은 자리는 기다려주지 않으니까.</h2>
          <p>
            좌석을 고르면 바로 선점. <span>5분 안에 모의 결제를 완료해 주세요.</span>
          </p>
        </div>
        <div className={styles.miniSeats} aria-hidden="true">
          {Array.from({ length: 12 }, (_, i) => (
            <span key={i} className={i === 5 || i === 6 ? styles.held : undefined} />
          ))}
        </div>
        <Link to={routes.showtimes()} className={styles.bannerAction}>
          내 자리 찾아보기 <Icon name="arrow-right" size={17} />
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
