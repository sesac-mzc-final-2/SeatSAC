import { useInfiniteQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { GradeBadge, MoviePoster, movieListQuery, type MovieSummary } from "@/entities/movie";
import { routes } from "@/shared/config";
import { ButtonLink, Icon } from "@/shared/ui";
import styles from "./HomeHero.module.css";

const FEATURED_COUNT = 3;
/** 자동으로 다음 영화로 넘어가는 간격 */
const AUTO_SLIDE_MS = 5000;

/** 포스터가 있는 영화를 앞에서부터 3편, 모자라면 포스터 없는 영화로 채운다 */
function pickFeatured(movies: MovieSummary[]): MovieSummary[] {
  const withPoster = movies.filter((m) => m.posterUrl !== null);
  const rest = movies.filter((m) => m.posterUrl === null);
  return [...withPoster, ...rest].slice(0, FEATURED_COUNT);
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * W1 상단 히어로: 상영중 영화 3편을 5초마다 돌려 가며 보여 준다 (목록 쿼리와 캐시를 공유).
 * 영화 정보만 담는다 — 서비스 소개 문구는 아래 안내 줄(HomePage)에 둔다.
 */
export function HomeHero() {
  const [slide, setSlide] = useState(0);
  // 마우스를 올렸거나 안쪽에 포커스가 있으면 자동 넘김을 멈춘다 (읽거나 누르는 중에 바뀌지 않게)
  const [paused, setPaused] = useState(false);
  const query = useInfiniteQuery(movieListQuery({ status: "NOW_SHOWING", size: 20 }));
  const featured = pickFeatured(query.data?.pages[0]?.content ?? []);
  const count = featured.length;
  const index = count > 0 ? slide % count : 0;
  const selected = featured[index];

  useEffect(() => {
    if (count < 2 || paused || prefersReducedMotion()) return;
    // index 가 바뀔 때마다 다시 걸어, 직접 넘긴 직후에도 5초를 온전히 보여 준다
    const timer = window.setTimeout(() => setSlide((index + 1) % count), AUTO_SLIDE_MS);
    return () => window.clearTimeout(timer);
  }, [count, index, paused]);

  if (!selected) {
    return <section className={styles.hero} aria-label="추천 영화" aria-busy={query.isPending} />;
  }

  return (
    <section
      className={styles.hero}
      aria-label="추천 영화"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setPaused(false);
      }}
    >
      {selected.posterUrl && (
        <div key={selected.movieId} className={styles.art} style={{ backgroundImage: `url(${selected.posterUrl})` }} />
      )}
      <div className={styles.fade} />
      <div key={`text-${selected.movieId}`} className={styles.text}>
        <span className={styles.kicker}>상영중</span>
        <h1 className={styles.headline}>{selected.title}</h1>
        <div className={styles.meta}>
          <GradeBadge grade={selected.viewGrade} />
          {selected.titleEn && <span className={styles.movieEn}>{selected.titleEn}</span>}
        </div>
      </div>
      <div className={styles.actions}>
        <div className={styles.buttons}>
          <ButtonLink variant="primary" to={routes.showtimes({ movieId: selected.movieId })}>
            예매하기 <Icon name="arrow-right" size={17} />
          </ButtonLink>
          <ButtonLink variant="ghost" to={routes.movie(selected.movieId)}>
            영화 상세 <Icon name="arrow-up-right" size={16} />
          </ButtonLink>
        </div>
        {count > 1 && (
          <div className={styles.slider}>
            <div className={styles.lines}>
              {featured.map((movie, i) => (
                <button
                  key={movie.movieId}
                  type="button"
                  className={styles.line}
                  aria-pressed={i === index}
                  aria-label={`${movie.title} 보기`}
                  onClick={() => setSlide(i)}
                />
              ))}
            </div>
            <span>
              <b>{String(index + 1).padStart(2, "0")}</b> / {String(count).padStart(2, "0")}
            </span>
            <button type="button" className={styles.arrow} aria-label="이전 영화" onClick={() => setSlide((index + count - 1) % count)}>
              <Icon name="chevron-left" size={18} />
            </button>
            <button type="button" className={styles.arrow} aria-label="다음 영화" onClick={() => setSlide((index + 1) % count)}>
              <Icon name="chevron-right" size={18} />
            </button>
          </div>
        )}
      </div>
      <div key={`poster-${selected.movieId}`} className={styles.poster}>
        <MoviePoster movie={selected} eager />
      </div>
    </section>
  );
}
