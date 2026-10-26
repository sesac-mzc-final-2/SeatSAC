import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { movieDetailQuery } from "@/entities/movie";
import { moviesIn, screeningListQuery, type Screening } from "@/entities/screening";
import { theaterListQuery } from "@/entities/theater";
import { useEnterSeatSelect } from "@/features/enter-seat-select";
import { DateTabs, MovieSelect, TheaterTabs, useShowtimesFilter } from "@/features/showtimes-filter";
import { formatDateKo, useDocumentTitle } from "@/shared/lib";
import { Button, EmptyState, ErrorState, LoadingBlock } from "@/shared/ui";
import { ShowtimeBoard } from "@/widgets/showtime-board";
import styles from "./ShowtimesPage.module.css";

/**
 * W3 상영시간 선택 (CAT-03): 극장(배치 있는 4곳) → 날짜(7일) → 영화별·상영관별 회차.
 * 회차는 극장·날짜로 한 번 받아 두고 영화 필터는 화면에서 건다 (영화 드롭다운이 그날 편성에서 나온다).
 */
export function ShowtimesPage() {
  useDocumentTitle("상영시간");

  const theatersQuery = useQuery(theaterListQuery({ hasLayout: true }));
  const theaters = useMemo(() => theatersQuery.data?.items ?? [], [theatersQuery.data]);
  const theaterIds = useMemo(
    () => (theatersQuery.isSuccess ? theaters.map((t) => t.theaterId) : null),
    [theatersQuery.isSuccess, theaters],
  );
  const { filter, setTheater, setDate, setMovie } = useShowtimesFilter(theaterIds);

  const screeningsQuery = useQuery({
    ...screeningListQuery({ date: filter.date, theaterId: filter.theaterId ?? 0 }),
    enabled: filter.theaterId !== null,
  });
  const screenings = useMemo(() => screeningsQuery.data?.items ?? [], [screeningsQuery.data]);
  const movieOptions = useMemo(() => moviesIn(screenings), [screenings]);

  const selectedMovieMissing =
    filter.movieId !== null && screeningsQuery.isSuccess && !movieOptions.some((m) => m.movieId === filter.movieId);
  const selectedMovieQuery = useQuery({
    ...movieDetailQuery(filter.movieId ?? 0),
    enabled: selectedMovieMissing,
  });
  const selectedMovieTitle = selectedMovieQuery.data?.title ?? null;

  const visible: Screening[] = useMemo(
    () => (filter.movieId === null ? screenings : screenings.filter((s) => s.movie.movieId === filter.movieId)),
    [screenings, filter.movieId],
  );

  const enterSeatSelect = useEnterSeatSelect();

  const renderResults = () => {
    if (theatersQuery.isPending) return <LoadingBlock label="극장 정보를 불러오는 중…" />;
    if (theatersQuery.isError) {
      return (
        <ErrorState error={theatersQuery.error} title="극장 목록을 불러오지 못했습니다" onRetry={() => theatersQuery.refetch()} />
      );
    }
    if (theaters.length === 0) return <EmptyState title="예매할 수 있는 극장이 없습니다" />;
    if (screeningsQuery.isPending) return <LoadingBlock label="상영 회차를 불러오는 중…" />;
    if (screeningsQuery.isError) {
      return (
        <ErrorState error={screeningsQuery.error} title="상영 회차를 불러오지 못했습니다" onRetry={() => screeningsQuery.refetch()} />
      );
    }
    if (screenings.length === 0) {
      return (
        <EmptyState
          title="선택한 날짜에 남은 상영이 없습니다"
          description="다른 날짜나 극장을 골라 주세요. 오늘은 이미 시작한 회차를 제외합니다."
        />
      );
    }
    if (visible.length === 0) {
      return (
        <EmptyState
          title={`${selectedMovieTitle ?? "선택한 영화"}의 상영이 없습니다`}
          description="이 극장·날짜에는 편성이 없습니다. 다른 날짜를 고르거나 전체 영화를 보세요."
          action={<Button onClick={() => setMovie(null)}>전체 영화 보기</Button>}
        />
      );
    }
    return <ShowtimeBoard screenings={visible} onSelect={(s) => enterSeatSelect(s.screeningId)} />;
  };

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>상영시간</h1>

      {theaters.length > 0 && <TheaterTabs theaters={theaters} value={filter.theaterId} onChange={setTheater} />}
      <DateTabs value={filter.date} onChange={setDate} />

      <div className={styles.toolbar}>
        <MovieSelect options={movieOptions} value={filter.movieId} onChange={setMovie} missingLabel={selectedMovieTitle} />
        <span className={styles.hint}>
          <span className={styles.dateLabel}>{formatDateKo(filter.date)}</span> · 오늘은 지난 회차 제외 · 시각은 KST
        </span>
      </div>

      {renderResults()}
    </div>
  );
}
