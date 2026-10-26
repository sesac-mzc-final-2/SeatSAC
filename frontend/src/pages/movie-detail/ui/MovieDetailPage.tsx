import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link, useParams } from "react-router";
import { GradeBadge, MoviePoster, movieDetailQuery } from "@/entities/movie";
import { isApiError } from "@/shared/api";
import { routes } from "@/shared/config";
import { formatDateDots, hasHangul, useDocumentTitle } from "@/shared/lib";
import { ButtonLink, EmptyState, ErrorState, LoadingBlock } from "@/shared/ui";
import styles from "./MovieDetailPage.module.css";

const CAST_LIMIT = 5;

function MovieNotFound() {
  useDocumentTitle("영화를 찾을 수 없습니다");
  return (
    <EmptyState
      title="영화를 찾을 수 없습니다"
      description="주소가 잘못되었거나 상영이 끝난 영화입니다."
      action={<ButtonLink to={routes.home()}>영화 목록으로</ButtonLink>}
    />
  );
}

/** W2 영화 상세 (CAT-02). TMDB 보충 항목은 null이면 그 줄을 숨긴다 */
export function MovieDetailPage() {
  const { movieId } = useParams();
  const id = Number(movieId);
  const validId = Number.isInteger(id) && id > 0;
  const query = useQuery({ ...movieDetailQuery(id), enabled: validId });
  useDocumentTitle(query.data?.title);

  if (!validId || isApiError(query.error, 404)) return <MovieNotFound />;
  if (query.isPending) return <LoadingBlock label="영화 정보를 불러오는 중…" />;
  if (query.isError) {
    return <ErrorState error={query.error} title="영화 정보를 불러오지 못했습니다" onRetry={() => query.refetch()} />;
  }

  const movie = query.data;
  const rows: Array<[string, ReactNode]> = [];
  if (movie.runtimeMinutes !== null) rows.push(["러닝타임", `${movie.runtimeMinutes}분`]);
  if (movie.releaseDate) rows.push(["개봉일", formatDateDots(movie.releaseDate)]);
  if (movie.director) rows.push(["감독", movie.director]);
  if (movie.cast.length > 0) rows.push(["출연", movie.cast.slice(0, CAST_LIMIT).join(", ")]);

  return (
    <article className={styles.page}>
      <Link to={routes.home()} className={styles.back}>
        ← 영화 목록
      </Link>

      <section className={styles.hero}>
        <MoviePoster movie={movie} className={styles.poster} />
        <div className={styles.info}>
          <div className={styles.titleBlock}>
            <div className={styles.meta}>
              <GradeBadge grade={movie.viewGrade} />
              {movie.genres.length > 0 && <span>{movie.genres.join(" · ")}</span>}
            </div>
            <h1 className={styles.title}>{movie.title}</h1>
            {movie.titleEn && movie.titleEn !== movie.title && <p className={styles.titleEn}>{movie.titleEn}</p>}
          </div>

          {rows.length > 0 && (
            <dl className={styles.rows}>
              {rows.map(([label, value]) => (
                <div key={label} style={{ display: "contents" }}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
          )}

          <div className={styles.actions}>
            <ButtonLink variant="primary" size="lg" to={routes.showtimes({ movieId: movie.movieId })}>
              예매하기
            </ButtonLink>
          </div>
        </div>
      </section>

      {movie.synopsis && (
        <section className={styles.synopsis} aria-labelledby="synopsis-heading">
          <h2 id="synopsis-heading" className={styles.synopsisTitle}>
            줄거리
          </h2>
          <p className={styles.synopsisText}>{movie.synopsis}</p>
          {!hasHangul(movie.synopsis) && <p className={styles.note}>한국어 줄거리가 없어 영어 원문을 보여 줍니다.</p>}
        </section>
      )}

      <p className={styles.attribution}>
        장르·줄거리·감독·출연·개봉일·포스터 출처: TMDB · <Link to={routes.credits()}>출처·크레딧</Link>
      </p>
    </article>
  );
}
