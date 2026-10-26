import { useMemo } from "react";
import { Link } from "react-router";
import { GradeBadge } from "@/entities/movie";
import { groupScreenings, ScreeningTimeButton, screenTypeLabel, type Screening } from "@/entities/screening";
import { routes } from "@/shared/config";
import { joinNonEmpty } from "@/shared/lib";
import styles from "./ShowtimeBoard.module.css";

interface Props {
  screenings: Screening[];
  onSelect: (screening: Screening) => void;
}

/** W3 결과: 영화별 → 상영관별 시작 시각 버튼. 영화 제목 옆에 러닝타임은 표시하지 않는다 (W-2) */
export function ShowtimeBoard({ screenings, onSelect }: Props) {
  const groups = useMemo(() => groupScreenings(screenings), [screenings]);

  return (
    <div className={styles.board}>
      {groups.map((group) => (
        <section key={group.movie.movieId} className={styles.movie} aria-labelledby={`movie-${group.movie.movieId}`}>
          <div className={styles.movieHead}>
            <GradeBadge grade={group.movie.viewGrade} />
            <h2 id={`movie-${group.movie.movieId}`}>
              <Link to={routes.movie(group.movie.movieId)} className={styles.movieTitle}>
                {group.movie.title}
              </Link>
            </h2>
          </div>
          {group.auditoriums.map((hall) => (
            <div key={hall.auditorium.auditoriumId} className={styles.row}>
              <div className={styles.hall}>
                <strong className={styles.hallName}>{hall.auditorium.name}</strong>
                <span className={styles.hallMeta}>
                  {joinNonEmpty([screenTypeLabel(hall.auditorium.screenType), hall.filmFormat, `${hall.totalSeats}석`])}
                </span>
              </div>
              <div className={styles.times}>
                {hall.screenings.map((screening) => (
                  <ScreeningTimeButton key={screening.screeningId} screening={screening} onSelect={onSelect} />
                ))}
              </div>
            </div>
          ))}
        </section>
      ))}
    </div>
  );
}
