import type { ReactNode } from "react";
import { Link } from "react-router";
import { routes } from "@/shared/config";
import { MoviePoster } from "./MoviePoster";
import type { MovieSummary } from "../model/types";
import styles from "./MovieCard.module.css";

interface Props {
  movie: MovieSummary;
  /** 카드 아래 버튼 자리 (예: 「예매」 링크). 어디로 보낼지는 쓰는 쪽이 정한다 */
  actions?: ReactNode;
}

/** W1 목록 카드: 포스터(없으면 제목 카드) + 등급 + 제목(1줄, 말줄임). 러닝타임은 표시하지 않는다 (W-2) */
export function MovieCard({ movie, actions }: Props) {
  const detailHref = routes.movie(movie.movieId);
  return (
    <article className={styles.card} aria-label={movie.title}>
      <Link to={detailHref} className={styles.posterLink} aria-label={`${movie.title} 상세`}>
        <MoviePoster movie={movie} showGrade />
      </Link>
      {/* 1줄을 넘는 제목은 말줄임(…)되므로 전체 제목은 title 툴팁으로 */}
      <Link to={detailHref} className={styles.title} title={movie.title}>
        {movie.title}
      </Link>
      {actions && <div className={styles.actions}>{actions}</div>}
    </article>
  );
}
