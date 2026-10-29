import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router";
import { auditoriumLayoutQuery } from "@/entities/auditorium";
import { availableCount } from "@/entities/booking";
import { screenTypeLabel, screeningDetailQuery } from "@/entities/screening";
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
import { formatDateKo, timeOfIso, useDocumentTitle } from "@/shared/lib";
import { EmptyState, ErrorState, LoadingBlock, PageHeading, useToast } from "@/shared/ui";
import { SeatBoard } from "@/widgets/seat-board";
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

  const screeningQuery = useQuery({
    ...screeningDetailQuery(screeningId),
    enabled: Number.isInteger(screeningId),
  });
  const screening = screeningQuery.data ?? null;

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
    onReleased: () => {
      setActiveHold(null);
      board.reload();
      toast.show("선택을 취소했어요. 좌석을 다시 고를 수 있습니다.");
    },
    onFailed: (message) => toast.show(message, { tone: "error" }),
  });

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

    return (
      <SeatBoard
        layout={layoutQuery.data}
        statusOfSeat={board.statusOfSeat}
        choice={choice}
        onToggle={(seat) => {
          if (!choice.toggle(seat)) toast.show("한 번에 최대 4석까지 선택할 수 있어요.");
        }}
        onSubmit={() => hold.hold({ screeningId, seatIds: choice.seatIds })}
        holdPending={hold.pending}
        live={board.live}
        remaining={remaining}
        disabled={activeHold !== null}
      />
    );
  };

  if (!Number.isInteger(screeningId)) {
    return <EmptyState as="h1" title="잘못된 회차 주소예요" description="상영시간표에서 회차를 다시 선택해 주세요." />;
  }

  return (
    <div className={styles.page}>
      <PageHeading
        eyebrow={screening ? `${screening.theater.name} · ${screening.auditorium.name}` : undefined}
        title={screening ? screening.movie.title : "좌석 선택"}
        description={
          screening
            ? `${formatDateKo(screening.businessDate)} ${timeOfIso(screening.startsAt)} ~ ${timeOfIso(screening.endsAt)} · ${screenTypeLabel(screening.auditorium.screenType)}${screening.filmFormat ? ` · ${screening.filmFormat}` : ""}`
            : undefined
        }
        back={{ to: routes.showtimes({ theaterId: screening?.theater.theaterId }), label: "상영시간표" }}
      >
        <BookingSteps current={1} />
      </PageHeading>

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
