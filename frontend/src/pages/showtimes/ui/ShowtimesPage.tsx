import { useQuery } from "@tanstack/react-query";
import { useMemo } from "react";
import { Link } from "react-router";
import { movieDetailQuery } from "@/entities/movie";
import { moviesIn, screeningListQuery, type Screening } from "@/entities/screening";
import { useSession } from "@/entities/session";
import { theaterListQuery } from "@/entities/theater";
import { myPreferencesQuery, timeSlotLabel, timeSlotOfHour } from "@/entities/user";
import { BookingSteps } from "@/features/booking-steps";
import { useEnterSeatSelect } from "@/features/enter-seat-select";
import { DateTabs, MovieSelect, TheaterTabs, useShowtimesFilter } from "@/features/showtimes-filter";
import { routes } from "@/shared/config";
import { formatDateDots, timeOfIso, useDocumentTitle } from "@/shared/lib";
import { Button, EmptyState, ErrorState, Icon, LoadingBlock, PageHeading } from "@/shared/ui";
import { ShowtimeBoard } from "@/widgets/showtime-board";
import styles from "./ShowtimesPage.module.css";

/**
 * W3 상영시간표 (CAT-03): 극장(배치 있는 4곳) → 날짜(7일) → 영화별·상영관별 회차.
 * 회차는 극장·날짜로 한 번 받아 두고 영화 필터는 화면에서 건다 (영화 드롭다운이 그날 편성에서 나온다).
 * 로그인 사용자는 마이페이지 선호(USR-05)가 반영된다: 1순위 선호 극장을 기본 선택, 선호 극장 탭·선호 시간대 회차에 하트.
 * 선호를 못 받으면(비로그인·실패) 선호 없이 그대로 보여 준다.
 */
export function ShowtimesPage() {
  useDocumentTitle("상영시간표");

  const session = useSession();
  const loggedIn = session.status === "authenticated";
  // 선호는 보조 정보라 다시 시도하지 않는다 — 실패하면 기다리지 않고 선호 없이 연다
  const preferencesQuery = useQuery({ ...myPreferencesQuery(), enabled: loggedIn, retry: false });
  const preferences = preferencesQuery.data ?? null;
  const preferredTheaterIds = useMemo(
    () => [...(preferences?.preferredTheaters ?? [])].sort((a, b) => a.rank - b.rank).map((t) => t.theaterId),
    [preferences],
  );
  const preferredTimeSlot = preferences?.preferredTimeSlot ?? null;
  // 선호 극장으로 바로 열리도록, 로그인 확인·선호 조회가 끝날 때까지 극장을 고르지 않는다 (첫 극장 → 선호 극장으로 깜빡이지 않게)
  const waitingForPreferences = session.status === "loading" || (loggedIn && preferencesQuery.isPending);

  const theatersQuery = useQuery(theaterListQuery({ hasLayout: true }));
  const theaters = useMemo(() => theatersQuery.data?.items ?? [], [theatersQuery.data]);
  const theaterIds = useMemo(
    () => (theatersQuery.isSuccess && !waitingForPreferences ? theaters.map((t) => t.theaterId) : null),
    [theatersQuery.isSuccess, waitingForPreferences, theaters],
  );
  const { filter, setTheater, setDate, setMovie } = useShowtimesFilter(theaterIds, preferredTheaterIds);
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

  const isPreferred = useMemo(
    () =>
      preferredTimeSlot === null
        ? undefined
        : (s: Screening) => timeSlotOfHour(Number(timeOfIso(s.startsAt).slice(0, 2))) === preferredTimeSlot,
    [preferredTimeSlot],
  );
  const preferenceHint = [
    preferredTheaterIds.some((id) => theaters.some((t) => t.theaterId === id)) ? "선호 극장" : null,
    preferredTimeSlot ? `선호 시간대(${timeSlotLabel(preferredTimeSlot)})` : null,
  ].filter(Boolean);

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
    return (
      <ShowtimeBoard screenings={visible} onSelect={(s) => enterSeatSelect(s.screeningId)} isPreferred={isPreferred} />
    );
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
        {theaters.length > 0 && (
          <TheaterTabs
            theaters={theaters}
            value={filter.theaterId}
            onChange={setTheater}
            preferredTheaterIds={preferredTheaterIds}
          />
        )}
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

      {preferenceHint.length > 0 && (
        <p className={styles.preferenceHint}>
          <Icon name="heart" size={12} fill="currentColor" className={styles.preferenceHeart} />
          <span>내 {preferenceHint.join(" · ")}</span>
          <Link to={routes.myPage("preferences")}>선호 바꾸기</Link>
        </p>
      )}

      {renderResults()}
    </div>
  );
}
