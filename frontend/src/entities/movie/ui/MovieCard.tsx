import type { ReactNode } from "react";
import { Link } from "react-router";
import { routes } from "@/shared/config";
import { GradeBadge } from "./GradeBadge";
import { MoviePoster } from "./MoviePoster";
import type { MovieSummary } from "../model/types";
import styles from "./MovieCard.module.css";

interface Props {
  movie: MovieSummary;
  /** 포스터 위에 올라오는 주 버튼 (예: 「예매하기」). 어디로 보낼지는 쓰는 쪽이 정한다 */
  actions?: ReactNode;
  eager?: boolean;
}

/** W1 목록 카드: 포스터(없으면 제목 카드) + 등급 + 제목(1줄, 말줄임) + 영문 제목. 러닝타임은 표시하지 않는다 (W-2) */
export function MovieCard({ movie, actions, eager = false }: Props) {
  const detailHref = routes.movie(movie.movieId);
  return (
    <article className={styles.card} aria-label={movie.title}>
      <div className={styles.posterWrap}>
        <Link to={detailHref} className={styles.posterLink} aria-label={`${movie.title} 상세 보기`}>
          <MoviePoster movie={movie} eager={eager} />
        </Link>
        <span className={styles.grade}>
          <GradeBadge grade={movie.viewGrade} />
        </span>
        <div className={styles.actions}>
          {actions}
          <Link to={detailHref} className={styles.detail}>
            자세히 보기
          </Link>
        </div>
      </div>
      <Link to={detailHref} className={styles.title} title={movie.title}>
        <h3>{movie.title}</h3>
      </Link>
      {movie.titleEn && (
        <p className={styles.english} title={movie.titleEn}>
          {movie.titleEn}
        </p>
      )}
    </article>
  );
}
