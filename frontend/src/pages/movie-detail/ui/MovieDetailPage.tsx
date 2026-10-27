import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router";
import { GradeBadge, MoviePoster, movieDetailQuery } from "@/entities/movie";
import { isApiError } from "@/shared/api";
import { routes } from "@/shared/config";
import { formatDateDots, hasHangul, useDocumentTitle } from "@/shared/lib";
import { ButtonLink, EmptyState, ErrorState, Icon, LoadingBlock } from "@/shared/ui";
import styles from "./MovieDetailPage.module.css";

const CAST_LIMIT = 5;
const NOT_FOUND_TITLE = "영화를 찾을 수 없어요";

function MovieNotFound() {
  return (
    <EmptyState
      icon="alert-circle"
      title={NOT_FOUND_TITLE}
      description="주소가 잘못되었거나 상영이 끝난 영화입니다."
      action={
        <ButtonLink variant="primary" to={routes.home()}>
          상영중 영화 보기 <Icon name="arrow-right" />
        </ButtonLink>
      }
    />
  );
}

/** W2 영화 상세 (CAT-02). TMDB 보충 항목은 null이면 그 줄을 숨긴다 */
export function MovieDetailPage() {
  const { movieId } = useParams();
  const id = Number(movieId);
  const validId = Number.isInteger(id) && id > 0;
  const query = useQuery({ ...movieDetailQuery(id), enabled: validId });
  const notFound = !validId || isApiError(query.error, 404);
  // 문서 제목은 부모에서 한 번만 정한다 (자식 effect 뒤에 부모 effect 가 돌아 덮어쓰는 문제 방지)
  useDocumentTitle(notFound ? NOT_FOUND_TITLE : query.data?.title);

  if (notFound) return <MovieNotFound />;
  if (query.isPending) return <LoadingBlock label="영화 정보를 불러오는 중이에요" />;
  if (query.isError) {
    return <ErrorState error={query.error} title="영화 정보를 불러오지 못했어요" onRetry={() => query.refetch()} />;
  }

  const movie = query.data;
  const cast = movie.cast.slice(0, CAST_LIMIT);

  return (
    <article className={styles.page}>
      {movie.posterUrl && <div className={styles.backdrop} style={{ backgroundImage: `url(${movie.posterUrl})` }} />}
      <Link to={routes.home()} className={styles.back}>
        <Icon name="arrow-left" /> 상영중 영화
      </Link>

      <section className={styles.hero}>
        <MoviePoster movie={movie} eager className={styles.poster} />
        <div className={styles.content}>
          <p className="eyebrow">지금, 스크린에서</p>
          <div className={styles.titleRow}>
            <GradeBadge grade={movie.viewGrade} />
            <h1 className={styles.title}>{movie.title}</h1>
          </div>
          {movie.titleEn && movie.titleEn !== movie.title && <p className={styles.english}>{movie.titleEn}</p>}

          <div className={styles.meta}>
            {movie.genres.length > 0 && (
              <span>
                <Icon name="film" size={15} />
                {movie.genres.join(" · ")}
              </span>
            )}
            {movie.releaseDate && (
              <span>
                <Icon name="calendar" size={15} />
                {formatDateDots(movie.releaseDate)} 개봉
              </span>
            )}
            {movie.runtimeMinutes !== null && (
              <span>
                <Icon name="clock" size={15} />
                {movie.runtimeMinutes}분
              </span>
            )}
          </div>

          {movie.director && (
            <dl className={styles.credits}>
              <dt>감독</dt>
              <dd>{movie.director}</dd>
            </dl>
          )}
          {cast.length > 0 && (
            <dl className={styles.credits}>
              <dt>출연</dt>
              <dd>{cast.join(", ")}</dd>
            </dl>
          )}

          <ButtonLink variant="primary" className={styles.book} to={routes.showtimes({ movieId: movie.movieId })}>
            상영시간 보기 <Icon name="arrow-right" size={18} />
          </ButtonLink>
        </div>
      </section>

      {movie.synopsis && (
        <section className={styles.synopsis} aria-labelledby="synopsis-heading">
          <h2 id="synopsis-heading" className={styles.synopsisTitle}>
            줄거리
          </h2>
          <p className={styles.synopsisText}>{movie.synopsis}</p>
          {!hasHangul(movie.synopsis) && <p className="micro">한국어 줄거리가 없어 영어 원문을 보여 줍니다.</p>}
          <div className={styles.synopsisEnd} aria-hidden="true">
            <span />
            <Icon name="film" size={18} strokeWidth={1.2} />
            <span />
          </div>
        </section>
      )}

      <p className={styles.attribution}>
        장르·줄거리·감독·출연·개봉일·포스터 출처: TMDB · <Link to={routes.credits()}>출처·크레딧</Link>
      </p>
    </article>
  );
}
