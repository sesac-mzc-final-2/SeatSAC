import type { ReactNode } from "react";
import { Link } from "react-router";
import { routes } from "@/shared/config";
import { GradeBadge } from "./GradeBadge";
import { MoviePoster } from "./MoviePoster";
import type { MovieSummary } from "../model/types";
import styles from "./MovieCard.module.css";

interface Props {
  movie: MovieSummary;
  /** 카드 맨 아래 항상 보이는 주 버튼 (예: 「예매하기」). 어디로 보낼지는 쓰는 쪽이 정한다 */
  actions?: ReactNode;
  eager?: boolean;
}

/**
 * W1 목록 카드: 포스터(없으면 제목 카드) + 등급 + 제목(1줄, 말줄임) + 영문 제목 + 주 버튼. 러닝타임은 표시하지 않는다 (W-2).
 * 포스터·제목은 영화 상세로, 버튼은 actions 가 정한 곳으로 — 호버에 기대지 않아 마우스·터치 동작이 같다.
 */
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
      </div>
      <Link to={detailHref} className={styles.title} title={movie.title}>
        <h3>{movie.title}</h3>
      </Link>
      {movie.titleEn && (
        <p className={styles.english} title={movie.titleEn}>
          {movie.titleEn}
        </p>
      )}
      {actions && <div className={styles.actions}>{actions}</div>}
    </article>
  );
}
