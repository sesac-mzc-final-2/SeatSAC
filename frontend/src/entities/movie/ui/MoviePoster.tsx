import { useState } from "react";
import { Icon } from "@/shared/ui";
import type { MovieSummary } from "../model/types";
import styles from "./MoviePoster.module.css";

type Props = {
  movie: Pick<MovieSummary, "title" | "posterUrl"> & { titleEn?: string | null };
  /** 작은 자리(일정 목록·사이드바)용: 제목만 작게 */
  compact?: boolean;
  eager?: boolean;
  className?: string;
};

/** TMDB 포스터. posterUrl이 null이거나 못 불러오면 제목 카드 (롯데시네마 이미지는 쓰지 않는다) */
export function MoviePoster({ movie, compact = false, eager = false, className }: Props) {
  // 실패는 URL 단위로 기억한다 — 같은 자리에 다른 posterUrl 이 오면 다시 시도한다
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const showImage = movie.posterUrl !== null && failedUrl !== movie.posterUrl;

  return (
    <div className={[styles.frame, compact ? styles.compact : "", className].filter(Boolean).join(" ")}>
      {showImage ? (
        <img
          className={styles.image}
          src={movie.posterUrl ?? undefined}
          alt=""
          loading={eager ? "eager" : "lazy"}
          decoding="async"
          onError={() => setFailedUrl(movie.posterUrl)}
        />
      ) : (
        <div className={styles.fallback} aria-hidden="true">
          <Icon name="film" size={24} strokeWidth={1} />
          <span className={styles.fallbackTitle}>{movie.title}</span>
          {movie.titleEn && <small className={styles.fallbackNote}>{movie.titleEn}</small>}
          <div className={styles.fallbackLines} />
        </div>
      )}
    </div>
  );
}
