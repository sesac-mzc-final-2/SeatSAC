import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { GradeBadge, movieListQuery, type MovieSummary } from "@/entities/movie";
import { routes } from "@/shared/config";
import { ButtonLink, Icon } from "@/shared/ui";
import styles from "./HomeHero.module.css";

const HEADLINES = [
  ["영화의 시작,", "당신의 자리에서."],
  ["스크린을 가득 채울,", "짜릿한 순간."],
  ["오늘의 기분에 맞는,", "한 편을 골라보세요."],
] as const;

const DESCRIPTIONS = [
  "좋은 자리는 기다려주지 않으니까.\n지금 상영중인 영화를 가장 좋은 자리에서.",
  "선택한 좌석은 바로 선점되고,\n다른 화면에도 1초 안에 반영됩니다.",
  "4개 극장의 오늘 편성을 한눈에.\n회차를 고르면 좌석 선택으로 이어집니다.",
] as const;

const FEATURED_COUNT = 3;

/** 포스터가 있는 영화를 앞에서부터 3편, 모자라면 포스터 없는 영화로 채운다 */
function pickFeatured(movies: MovieSummary[]): MovieSummary[] {
  const withPoster = movies.filter((m) => m.posterUrl !== null);
  const rest = movies.filter((m) => m.posterUrl === null);
  return [...withPoster, ...rest].slice(0, FEATURED_COUNT);
}

/** W1 상단 와이드 히어로: 상영중 영화 중 3편을 돌려 가며 보여 준다 (목록 쿼리와 캐시를 공유) */
export function HomeHero() {
  const [slide, setSlide] = useState(0);
  const query = useInfiniteQuery(movieListQuery({ status: "NOW_SHOWING", size: 20 }));
  const featured = pickFeatured(query.data?.pages[0]?.content ?? []);
  const count = featured.length;
  const index = count > 0 ? slide % count : 0;
  const selected = featured[index];

  return (
    <section className={styles.hero} aria-label="추천 영화">
      {selected?.posterUrl && (
        <div key={selected.movieId} className={styles.art} style={{ backgroundImage: `url(${selected.posterUrl})` }} />
      )}
      <div className={styles.fade} />
      <div className={styles.content}>
        <span className={styles.kicker}>지금, 극장에서 만나요</span>
        <h1 className={styles.headline}>
          {HEADLINES[index % HEADLINES.length].map((line) => (
            <span key={line}>{line}</span>
          ))}
        </h1>
        <p className={styles.description}>{DESCRIPTIONS[index % DESCRIPTIONS.length]}</p>
        {selected && (
          <>
            <div className={styles.movie}>
              <GradeBadge grade={selected.viewGrade} />
              <strong>{selected.title}</strong>
              {selected.titleEn && <span className={styles.movieEn}>{selected.titleEn}</span>}
            </div>
            <div className={styles.buttons}>
              <ButtonLink variant="primary" to={routes.showtimes({ movieId: selected.movieId })}>
                상영시간 보기 <Icon name="arrow-right" size={17} />
              </ButtonLink>
              <ButtonLink variant="ghost" to={routes.movie(selected.movieId)}>
                영화 상세 <Icon name="arrow-up-right" size={16} />
              </ButtonLink>
            </div>
          </>
        )}
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
    </section>
  );
}
