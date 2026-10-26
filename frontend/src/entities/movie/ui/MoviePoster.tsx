import { useState } from "react";
import { GradeBadge } from "./GradeBadge";
import type { MovieSummary } from "../model/types";
import styles from "./MoviePoster.module.css";

type Props = {
  movie: Pick<MovieSummary, "title" | "posterUrl" | "viewGrade">;
  /** 포스터 위에 등급 배지를 겹칠지 (목록 카드) */
  showGrade?: boolean;
  className?: string;
};

/** TMDB 포스터. posterUrl이 null이거나 못 불러오면 제목 카드 (롯데시네마 이미지는 쓰지 않는다) */
export function MoviePoster({ movie, showGrade = false, className }: Props) {
  const [failed, setFailed] = useState(false);
  const showImage = movie.posterUrl !== null && !failed;

  return (
    <div className={[styles.frame, className].filter(Boolean).join(" ")}>
      {showImage ? (
        <img
          className={styles.image}
          src={movie.posterUrl ?? undefined}
          alt=""
          loading="lazy"
          decoding="async"
          onError={() => setFailed(true)}
        />
      ) : (
        <div className={styles.fallback} aria-hidden="true">
          <span />
          <span className={styles.fallbackTitle}>{movie.title}</span>
          <span className={styles.fallbackNote}>포스터 준비 중</span>
        </div>
      )}
      {showGrade && (
        <span className={styles.badge}>
          <GradeBadge grade={movie.viewGrade} />
        </span>
      )}
    </div>
  );
}
