import { useMemo } from "react";
import { Link } from "react-router";
import { GradeBadge } from "@/entities/movie";
import { groupScreenings, ScreeningTimeButton, screenTypeLabel, type Screening } from "@/entities/screening";
import { routes } from "@/shared/config";
import { joinNonEmpty } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import styles from "./ShowtimeBoard.module.css";

interface Props {
  screenings: Screening[];
  onSelect: (screening: Screening) => void;
}

/** W3 결과: 영화별 패널 → 상영관별 시작 시각 버튼. 영화 제목 옆에 러닝타임은 표시하지 않는다 (W-2) */
export function ShowtimeBoard({ screenings, onSelect }: Props) {
  const groups = useMemo(() => groupScreenings(screenings), [screenings]);

  return (
    <div className={styles.list}>
      {groups.map((group) => {
        const total = group.auditoriums.reduce((n, hall) => n + hall.screenings.length, 0);
        // 형식별로 나뉜 그룹이라 상영관 수는 고유 auditoriumId 로 센다
        const auditoriumCount = new Set(group.auditoriums.map((hall) => hall.auditorium.auditoriumId)).size;
        return (
          <article key={group.movie.movieId} className={`panel ${styles.movie}`} aria-labelledby={`movie-${group.movie.movieId}`}>
            <div className={styles.info}>
              <div className={styles.titleRow}>
                <GradeBadge grade={group.movie.viewGrade} />
                <h3 id={`movie-${group.movie.movieId}`}>
                  <Link to={routes.movie(group.movie.movieId)}>{group.movie.title}</Link>
                </h3>
              </div>
              <p className={styles.meta}>
                상영관 {auditoriumCount}곳 · 회차 {total}개
              </p>
              <Link to={routes.movie(group.movie.movieId)} className={styles.detail}>
                영화 정보 <Icon name="arrow-right" size={12} />
              </Link>
            </div>
            {group.auditoriums.map((hall) => (
              <div key={`${hall.auditorium.auditoriumId}:${hall.filmFormat ?? ""}`} className={styles.room}>
                <div className={styles.roomLabel}>
                  <Icon name="armchair" size={15} />
                  <strong>{hall.auditorium.name}</strong>
                  <span>· {joinNonEmpty([screenTypeLabel(hall.auditorium.screenType), hall.filmFormat])}</span>
                  <small>총 {hall.totalSeats}석</small>
                </div>
                <div className={styles.grid}>
                  {hall.screenings.map((screening) => (
                    <ScreeningTimeButton key={screening.screeningId} screening={screening} onSelect={onSelect} />
                  ))}
                </div>
              </div>
            ))}
          </article>
        );
      })}
    </div>
  );
}
