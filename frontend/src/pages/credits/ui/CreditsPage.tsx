import { Link } from "react-router";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import styles from "./CreditsPage.module.css";

/** 좌석 배치·편성을 수집한 날 (lotte-seed layout_import fetched_at). 재수집하면 함께 바꾼다 */
const LOTTE_COLLECTED_AT = "2026-10-14";
const LOTTE_THEATERS = ["건대입구", "월드타워", "영등포", "부산본점"];

/** W10 출처·크레딧 — 준수 조건(기능명세서 5장): TMDB 고지, 롯데시네마 출처·수집 시각, 비영리 안내 */
export function CreditsPage() {
  useDocumentTitle("출처·크레딧");
  return (
    <div className={styles.page}>
      <h1 className={styles.title}>출처·크레딧</h1>

      <section className={styles.section} aria-labelledby="credits-project">
        <h2 id="credits-project" className={styles.heading}>
          프로젝트
        </h2>
        <p className={styles.text}>
          SeatSAC(싯싹)은 비영리 교육 목적으로 만든 영화 예매 시연 사이트입니다. 실시간 좌석 선점과 무중단 배포를
          보여 주기 위한 것으로, 실제 예매·결제는 이루어지지 않습니다.
        </p>
      </section>

      <section className={styles.section} aria-labelledby="credits-lotte">
        <h2 id="credits-lotte" className={styles.heading}>
          좌석 배치·상영 일정
        </h2>
        <p className={styles.text}>
          출처: 롯데시네마 · {LOTTE_COLLECTED_AT} 수집. {LOTTE_THEATERS.join(", ")} 4개 지점 일반관·리클라이너관의
          좌석 배치와 하루치 편성을 날짜만 옮겨 사용합니다.
        </p>
        <p className={styles.note}>
          좌석 점유·요금·잔여석은 이 사이트가 따로 관리하며 롯데시네마의 실제 예매와 무관합니다. 롯데시네마의 포스터·이미지는
          사용하지 않습니다.
        </p>
      </section>

      <section className={styles.section} aria-labelledby="credits-tmdb">
        <h2 id="credits-tmdb" className={styles.heading}>
          영화 정보
        </h2>
        <div className={styles.tmdb}>
          <a
            className={styles.tmdbMark}
            href="https://www.themoviedb.org/"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="TMDB (The Movie Database), 새 창"
          >
            TMDB
          </a>
          <p className={styles.text} lang="en">
            This product uses the TMDB API but is not endorsed or certified by TMDB.
          </p>
        </div>
        <p className={styles.note}>
          장르·줄거리·감독·출연·개봉일·포스터는 TMDB에서 가져왔습니다. 한국어 정보가 없는 항목은 영어 원문을 보여 주거나
          표시하지 않습니다.
        </p>
      </section>

      <section className={styles.section} aria-labelledby="credits-oss">
        <h2 id="credits-oss" className={styles.heading}>
          만든 사람·도구
        </h2>
        <ul className={styles.list}>
          <li>새싹(SeSAC) × 메가존클라우드 최종 프로젝트 2팀</li>
          <li>React · Vite · Spring Boot · MySQL · Valkey · AWS(EKS · CloudFront · Cognito)</li>
        </ul>
      </section>

      <Link to={routes.home()} className={styles.back}>
        ← 홈으로
      </Link>
    </div>
  );
}
