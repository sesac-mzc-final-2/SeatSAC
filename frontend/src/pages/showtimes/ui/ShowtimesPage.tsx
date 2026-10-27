import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { movieDetailQuery } from "@/entities/movie";
import { moviesIn, screeningListQuery, type Screening } from "@/entities/screening";
import { theaterListQuery } from "@/entities/theater";
import { BookingSteps } from "@/features/booking-steps";
import { useEnterSeatSelect } from "@/features/enter-seat-select";
import { DateTabs, MovieSelect, TheaterTabs, useShowtimesFilter } from "@/features/showtimes-filter";
import { formatDateDots, useDocumentTitle } from "@/shared/lib";
import { Button, EmptyState, ErrorState, LoadingBlock, PageHeading } from "@/shared/ui";
import { ShowtimeBoard } from "@/widgets/showtime-board";
import styles from "./ShowtimesPage.module.css";

/**
 * W3 상영시간표 (CAT-03): 극장(배치 있는 4곳) → 날짜(7일) → 영화별·상영관별 회차.
 * 회차는 극장·날짜로 한 번 받아 두고 영화 필터는 화면에서 건다 (영화 드롭다운이 그날 편성에서 나온다).
 */
export function ShowtimesPage() {
  useDocumentTitle("상영시간표");

  const theatersQuery = useQuery(theaterListQuery({ hasLayout: true }));
  const theaters = useMemo(() => theatersQuery.data?.items ?? [], [theatersQuery.data]);
  const theaterIds = useMemo(
    () => (theatersQuery.isSuccess ? theaters.map((t) => t.theaterId) : null),
    [theatersQuery.isSuccess, theaters],
  );
  const { filter, setTheater, setDate, setMovie } = useShowtimesFilter(theaterIds);
  const selectedTheater = theaters.find((t) => t.theaterId === filter.theaterId) ?? null;

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
    if (theatersQuery.isPending) return <LoadingBlock label="극장 정보를 불러오는 중이에요" />;
    if (theatersQuery.isError) {
      return (
        <ErrorState error={theatersQuery.error} title="극장 목록을 불러오지 못했어요" onRetry={() => theatersQuery.refetch()} />
      );
    }
    if (theaters.length === 0) return <EmptyState title="예매할 수 있는 극장이 없어요" />;
    if (screeningsQuery.isPending) return <LoadingBlock label="상영 회차를 불러오는 중이에요" />;
    if (screeningsQuery.isError) {
      return (
        <ErrorState error={screeningsQuery.error} title="상영 회차를 불러오지 못했어요" onRetry={() => screeningsQuery.refetch()} />
      );
    }
    if (screenings.length === 0) {
      return (
        <div className="panel">
          <EmptyState
            title="선택한 날짜에 남은 상영이 없어요"
            description="다른 날짜나 극장을 골라 주세요. 오늘은 이미 시작한 회차를 제외합니다."
          />
        </div>
      );
    }
    if (visible.length === 0) {
      return (
        <div className="panel">
          <EmptyState
            title={`${selectedMovieTitle ?? "선택한 영화"}의 상영이 없어요`}
            description="이 극장·날짜에는 편성이 없습니다. 다른 날짜를 고르거나 모든 영화를 보세요."
            action={<Button onClick={() => setMovie(null)}>모든 영화 보기</Button>}
          />
        </div>
      );
    }
    return <ShowtimeBoard screenings={visible} onSelect={(s) => enterSeatSelect(s.screeningId)} />;
  };

  return (
    <div>
      <PageHeading
        eyebrow="나만의 영화 시간"
        title="상영시간표"
        description="가까운 극장, 편안한 시간. 마음에 드는 회차를 골라보세요."
      >
        <BookingSteps current={0} />
      </PageHeading>

      <section className={`panel ${styles.picker}`} aria-label="극장·날짜 선택">
        {theaters.length > 0 && <TheaterTabs theaters={theaters} value={filter.theaterId} onChange={setTheater} />}
        <DateTabs value={filter.date} onChange={setDate} />
      </section>

      <div className={styles.toolbar}>
        <div className={styles.toolbarTitle}>
          <h2>상영 일정</h2>
          <span>
            {selectedTheater ? `${selectedTheater.name} · ` : ""}
            {formatDateDots(filter.date)}
          </span>
        </div>
        <MovieSelect options={movieOptions} value={filter.movieId} onChange={setMovie} missingLabel={selectedMovieTitle} />
      </div>

      {renderResults()}
    </div>
  );
}
