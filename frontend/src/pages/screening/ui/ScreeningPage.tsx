import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router";
import { auditoriumLayoutQuery } from "@/entities/auditorium";
import { availableCount } from "@/entities/booking";
import { GradeBadge, movieDetailQuery } from "@/entities/movie";
import { screenTypeLabel, screeningDetailQuery, type Screening } from "@/entities/screening";
import { BookingSteps } from "@/features/booking-steps";
import { RequireLogin } from "@/features/require-login";
import {
  ActiveHoldBanner,
  holdFailureMessage,
  useReleaseHold,
  useSeatBoard,
  useSeatChoice,
  useSeatHold,
  type HoldFailure,
} from "@/features/seat-selection";
import { routes } from "@/shared/config";
import { formatDateDots, serverNow, timeOfIso, useDocumentTitle } from "@/shared/lib";
import { EmptyState, ErrorState, Icon, LoadingBlock, PageHeading, useToast } from "@/shared/ui";
import { SeatBoard, SeatBookingPanel } from "@/widgets/seat-board";
import styles from "./ScreeningPage.module.css";

interface ActiveHold {
  bookingId: number;
  expiresAt: string | null;
}

/**
 * W4 좌석 선택 (BKG-01·02·03·05). 로그인한 사람만 들어온다 (W3 → W4 경계).
 *
 * 좌석 상태는 BKG-01 스냅샷으로 받고 BKG-05 SSE로 따라간다 — 다른 사람이 잡은 좌석이
 * 1초 안에 내 화면에서도 막히는 것이 실시간 좌석 데모의 핵심이다.
 */
export function ScreeningPage() {
  return (
    <RequireLogin>
      <SeatSelect />
    </RequireLogin>
  );
}

function SeatSelect() {
  const params = useParams();
  const screeningId = Number(params.screeningId);
  const navigate = useNavigate();
  const toast = useToast();

  const [activeHold, setActiveHold] = useState<ActiveHold | null>(null);
  // 화면을 연 시각(서버 기준) — 이미 시작한 회차 안내용. 그 뒤에 시작하면 선점이 SCREENING_STARTED로 알려 준다
  const [openedAt] = useState(() => serverNow());

  const screeningQuery = useQuery({
    ...screeningDetailQuery(screeningId),
    enabled: Number.isInteger(screeningId),
  });
  const screening = screeningQuery.data ?? null;

  // 오른쪽 요약 패널의 포스터용 — 실패해도 제목 카드로 그린다
  const movieQuery = useQuery({
    ...movieDetailQuery(screening?.movie.movieId ?? 0),
    enabled: screening !== null,
  });

  const layoutQuery = useQuery({
    ...auditoriumLayoutQuery(screening?.auditorium.auditoriumId ?? 0, screening?.layoutVersion),
    enabled: screening !== null,
  });

  const board = useSeatBoard(screeningId);
  const choice = useSeatChoice();

  useDocumentTitle(screening ? `${screening.movie.title} 좌석 선택` : "좌석 선택");

  const onHoldFailed = (failure: HoldFailure) => {
    const message = holdFailureMessage(failure);
    switch (failure.kind) {
      case "seat-taken":
        // 서버가 거절한 좌석만 빼는 게 아니라 전체 상태를 다시 받는다 — 그 사이 다른 좌석도 바뀌었을 수 있다
        choice.clear();
        board.reload();
        toast.show(message, { tone: "error" });
        return;
      case "active-hold":
        if (failure.bookingId !== null) {
          setActiveHold({ bookingId: failure.bookingId, expiresAt: failure.expiresAt });
          choice.clear();
        }
        toast.show(message, { tone: "error" });
        return;
      case "screening-started":
        toast.show(message, { tone: "error" });
        void navigate(routes.showtimes({ theaterId: screening?.theater.theaterId }), { replace: true });
        return;
      default:
        toast.show(message, { tone: "error" });
    }
  };

  const hold = useSeatHold({
    onHeld: (result) => {
      choice.clear();
      void navigate(routes.bookingPay(result.bookingId));
    },
    onFailed: onHoldFailed,
  });

  const release = useReleaseHold({
    onReleased: ({ alreadyEnded }) => {
      setActiveHold(null);
      board.reload();
      toast.show(
        alreadyEnded
          ? "이전 선택은 이미 끝났어요. 좌석을 다시 고를 수 있습니다."
          : "선택을 취소했어요. 좌석을 다시 고를 수 있습니다.",
      );
    },
    onFailed: (message) => toast.show(message, { tone: "error" }),
  });

  // 고른 좌석을 다른 사람이 먼저 잡으면(SSE HELD·CONFIRMED) 바로 선택에서 뺀다 — 그대로 두면
  // 「선택됨」으로 보이다가 선점 요청이 409로 끝난다. 내 HELD일 수 있는 좌석은 건드리지 않는다:
  // 요청 중(응답보다 이벤트가 먼저 올 수 있다)이거나, 결과를 모르는 선점의 좌석(응답 유실 — 같은 좌석으로
  // 다시 누르면 같은 멱등 키로 첫 결과를 받아야 한다)
  const { statusOfSeat } = board;
  const { retain } = choice;
  const { pending: holdPending, unsettledSeatIds } = hold;
  useEffect(() => {
    if (holdPending) return;
    const mine = new Set(unsettledSeatIds);
    const taken = retain((seat) => mine.has(seat.seatId) || statusOfSeat(seat.seatId) === "AVAILABLE");
    if (taken.length > 0) {
      toast.show(`${taken.map((seat) => seat.seatLabel).join(", ")} 좌석을 방금 다른 분이 선택해 선택에서 뺐어요.`);
    }
  }, [statusOfSeat, retain, holdPending, unsettledSeatIds, toast]);

  const sellableSeatIds = useMemo(
    () => (layoutQuery.data?.seats ?? []).filter((seat) => seat.active).map((seat) => seat.seatId),
    [layoutQuery.data],
  );
  const remaining = board.board ? availableCount(board.board, sellableSeatIds) : 0;

  const renderBody = () => {
    if (screeningQuery.isPending) return <LoadingBlock label="회차 정보를 불러오는 중이에요" />;
    if (screeningQuery.isError) {
      return (
        <ErrorState
          error={screeningQuery.error}
          as="h2"
          title="회차 정보를 불러오지 못했어요"
          messages={{ NOT_FOUND: "회차를 찾을 수 없습니다. 상영시간표에서 다시 선택해 주세요." }}
          onRetry={() => void screeningQuery.refetch()}
        />
      );
    }
    if (!screening) return <EmptyState title="회차를 찾을 수 없어요" />;

    if (layoutQuery.isPending || board.isLoading) return <LoadingBlock label="좌석 배치를 불러오는 중이에요" />;
    if (layoutQuery.isError) {
      return (
        <ErrorState
          error={layoutQuery.error}
          as="h2"
          title="좌석 배치를 불러오지 못했어요"
          onRetry={() => void layoutQuery.refetch()}
        />
      );
    }
    if (board.error) {
      return (
        <ErrorState error={board.error} as="h2" title="좌석 상태를 불러오지 못했어요" onRetry={board.reload} />
      );
    }
    if (!layoutQuery.data || !board.board) return <EmptyState title="좌석 정보를 불러오지 못했어요" />;

    const started = new Date(screening.startsAt).getTime() <= openedAt;
    const locked = activeHold !== null || started;
    const toggle = (seat: Parameters<typeof choice.toggle>[0]) => {
      if (!choice.toggle(seat)) toast.show("한 번에 최대 4석까지 선택할 수 있어요.");
    };

    return (
      <>
        {started && (
          <div className={styles.notice} role="status">
            <Icon name="info" size={18} />
            <p>상영이 시작되어 더 이상 예매할 수 없어요.</p>
          </div>
        )}
        <div className={styles.layout}>
          <SeatBoard
            layout={layoutQuery.data}
            statusOfSeat={board.statusOfSeat}
            choice={choice}
            onToggle={toggle}
            live={board.live}
            totalSeats={sellableSeatIds.length}
            remaining={remaining}
            disabled={locked}
          />
          <SeatBookingPanel
            screening={screening}
            movie={movieQuery.data ?? null}
            seats={choice.seats}
            onRemove={toggle}
            onSubmit={() => hold.hold({ screeningId, seatIds: choice.seatIds })}
            pending={hold.pending}
            disabled={locked}
          />
        </div>
        <p className={styles.source}>
          좌석 배치 출처: 롯데시네마 · {formatDateDots(layoutQuery.data.source.fetchedAt.slice(0, 10))} 수집
          <span>준수 조건에 따른 출처 표기 · 교육용 배치</span>
        </p>
      </>
    );
  };

  if (!Number.isInteger(screeningId)) {
    return <EmptyState as="h1" title="잘못된 회차 주소예요" description="상영시간표에서 회차를 다시 선택해 주세요." />;
  }

  return (
    <div className={styles.page}>
      <PageHeading
        eyebrow="가장 좋은 자리에서"
        title="좌석 선택"
        back={{ to: routes.showtimes({ theaterId: screening?.theater.theaterId }), label: "상영시간표" }}
      >
        <BookingSteps current={1} />
      </PageHeading>

      {screening && <ScreeningSummary screening={screening} />}

      {activeHold && (
        <ActiveHoldBanner
          bookingId={activeHold.bookingId}
          expiresAt={activeHold.expiresAt}
          payHref={routes.bookingPay(activeHold.bookingId)}
          onRelease={() => release.release(activeHold.bookingId)}
          releasing={release.pending}
        />
      )}

      {renderBody()}
    </div>
  );
}

/** 시안의 회차 요약 줄 — 등급·제목 | 극장·상영관·유형 · 날짜·시간 */
function ScreeningSummary({ screening }: { screening: Screening }) {
  return (
    <div className={styles.summary}>
      <div>
        <GradeBadge grade={screening.movie.viewGrade} />
        <h2>{screening.movie.title}</h2>
        <span className={styles.divider} aria-hidden="true" />
        <span>
          {screening.theater.name} · {screening.auditorium.name} · {screenTypeLabel(screening.auditorium.screenType)}
          {screening.filmFormat ? ` · ${screening.filmFormat}` : ""}
        </span>
      </div>
      <p>
        {formatDateDots(screening.businessDate)}
        <b>
          {timeOfIso(screening.startsAt)} ~ {timeOfIso(screening.endsAt)}
        </b>
      </p>
    </div>
  );
}
