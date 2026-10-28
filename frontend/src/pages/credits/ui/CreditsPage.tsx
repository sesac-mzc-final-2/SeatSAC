import { Link } from "react-router";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { Icon, PageHeading } from "@/shared/ui";
import styles from "./CreditsPage.module.css";

/** 좌석 배치·편성을 수집한 날 (lotte-seed layout_import fetched_at). 재수집하면 함께 바꾼다 */
const LOTTE_COLLECTED_AT = "2026-10-14";
const LOTTE_THEATERS = ["건대입구", "월드타워", "영등포", "부산본점"];

const TEAM = [
  { name: "한채연", role: "gateway · catalog · user · 조회·회원 화면" },
  { name: "남궁일", role: "booking · payment · 좌석·결제 화면" },
  { name: "김종식", role: "Infra · DevOps (Terraform · CI/CD · GitOps · 관측)" },
  { name: "기술 스택", role: "React · Vite · Spring Boot · MySQL · Valkey · AWS EKS · CloudFront · Cognito" },
] as const;

/** W10 출처·크레딧 — 준수 조건(기능명세서 5장): TMDB 고지, 롯데시네마 출처·수집 시각, 비영리 안내 */
export function CreditsPage() {
  useDocumentTitle("출처·크레딧");
  return (
    <div>
      <PageHeading
        eyebrow="함께 만든 영화 경험"
        title="출처·크레딧"
        description="SeatSAC 교육용 시연을 위한 표기와 제작 정보를 안내합니다."
      />

      <section className={`panel ${styles.hero}`} aria-labelledby="credits-tmdb">
        <a
          className={styles.tmdb}
          href="https://www.themoviedb.org/"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="TMDB (The Movie Database), 새 창"
        >
          <span>TMDB</span>
          <small>API</small>
        </a>
        <div>
          <h2 id="credits-tmdb" className={styles.heading}>
            영화 정보 표기
          </h2>
          <p className={styles.disclaimer} lang="en">
            This product uses the TMDB API but is not endorsed or certified by TMDB.
          </p>
          <p className="muted">
            장르·줄거리·감독·출연·개봉일·포스터는 TMDB에서 가져왔습니다. 한국어 정보가 없는 항목은 영어 원문을 보여
            주거나 표시하지 않습니다.
          </p>
        </div>
      </section>

      <section className={styles.grid}>
        <article className={`panel ${styles.card}`}>
          <p className="eyebrow">데이터 출처</p>
          <h2 className={styles.heading}>좌석 배치·상영 편성</h2>
          <p className={styles.provenance}>
            출처: 롯데시네마 · {LOTTE_COLLECTED_AT} 수집
          </p>
          <p className={styles.sub}>
            {LOTTE_THEATERS.join(", ")} 4개 지점 일반관·리클라이너관의 좌석 배치와 하루치 편성을 날짜만 옮겨 사용합니다.
            좌석 점유·요금·잔여석은 이 사이트가 따로 관리하며 롯데시네마의 포스터·이미지는 사용하지 않습니다.
          </p>
        </article>
        <article className={`panel ${styles.card}`}>
          <p className="eyebrow">비영리 교육용 시연</p>
          <h2 className={styles.heading}>서비스 안내</h2>
          <p>SeatSAC(싯싹)은 비영리 교육 목적의 시연 사이트이며 실제 예매·결제가 이루어지지 않습니다.</p>
          <p className={styles.sub}>
            실시간 좌석 선점과 무중단 배포를 보여 주기 위한 것으로, 좌석 선점·결제·계정 흐름은 교육용 시나리오로만
            제공됩니다.
          </p>
        </article>
      </section>

      <section className={`panel ${styles.team}`} aria-labelledby="credits-team">
        <div className={styles.teamHead}>
          <div>
            <p className="eyebrow">SeatSAC 팀</p>
            <h2 id="credits-team" className={styles.heading}>
              만든 사람들
            </h2>
          </div>
          <span className="micro">새싹(SeSAC) × 메가존클라우드 최종 프로젝트 2팀</span>
        </div>
        <div className={styles.teamGrid}>
          {TEAM.map((member, index) => (
            <div key={member.name}>
              <span>{String(index + 1).padStart(2, "0")}</span>
              <strong>{member.name}</strong>
              <small>{member.role}</small>
            </div>
          ))}
        </div>
      </section>

      <Link to={routes.home()} className={styles.back}>
        <Icon name="arrow-left" /> 홈으로
      </Link>
    </div>
  );
}
