import { useInfiniteQuery } from "@tanstack/react-query";
import { MovieCard, movieListQuery } from "@/entities/movie";
import { routes } from "@/shared/config";
import { Button, ButtonLink, EmptyState, ErrorState, LoadingBlock } from "@/shared/ui";
import styles from "./MovieCatalog.module.css";

const PAGE_SIZE = 20;

/**
 * W1 상영중 영화 목록 (CAT-01). 상영예정 탭은 없다 (W-1: COMING_SOON은 시드상 항상 빈 배열).
 * 22편 = 2페이지 → 「더 보기」로 다음 페이지를 이어 붙인다.
 */
export function MovieCatalog() {
  const query = useInfiniteQuery(movieListQuery({ status: "NOW_SHOWING", size: PAGE_SIZE }));

  if (query.isPending) return <LoadingBlock label="상영중인 영화를 불러오는 중…" />;
  // 첫 페이지 실패 → 오류 블록. 다음 페이지 실패(isFetchNextPageError)는 data 가 남아 있으니 목록 아래에 표시한다
  const data = query.data;
  if (!data) {
    return <ErrorState error={query.error} title="영화 목록을 불러오지 못했습니다" onRetry={() => query.refetch()} />;
  }

  const movies = data.pages.flatMap((page) => page.content);
  const lastPage = data.pages[data.pages.length - 1];

  if (movies.length === 0) {
    return <EmptyState title="상영 중인 영화가 없습니다" description="편성이 들어오면 여기에 표시됩니다." />;
  }

  return (
    <div className={styles.wrap}>
      <p className={styles.summary}>롯데시네마 4개 지점 편성 기준 · {lastPage.totalElements}편</p>
      <div className={styles.grid}>
        {movies.map((movie) => (
          <MovieCard
            key={movie.movieId}
            movie={movie}
            actions={
              <ButtonLink variant="primary" to={routes.showtimes({ movieId: movie.movieId })}>
                예매
              </ButtonLink>
            }
          />
        ))}
      </div>
      {query.isFetchNextPageError && (
        <ErrorState error={query.error} title="다음 페이지를 불러오지 못했습니다" onRetry={() => query.fetchNextPage()} />
      )}
      {query.hasNextPage && !query.isFetchNextPageError && (
        <div className={styles.more}>
          <Button onClick={() => query.fetchNextPage()} disabled={query.isFetchingNextPage}>
            {query.isFetchingNextPage
              ? "불러오는 중…"
              : `더 보기 (${lastPage.page + 1} / ${lastPage.totalPages} 페이지)`}
          </Button>
        </div>
      )}
    </div>
  );
}
