import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  applyBufferedEvents,
  boardFromSnapshot,
  seatSnapshotQuery,
  statusOf,
  subscribeSeatEvents,
  type SeatBoard,
  type SeatEvent,
  type SeatStatus,
} from "@/entities/booking";

/** 이벤트에 빈틈이 있으면 이만큼 기다린 뒤 스냅샷을 다시 받는다 (계약 결정 13번) */
const GAP_REFETCH_DELAY_MS = 200;
/** 재연결 때 모두가 동시에 스냅샷을 요청하지 않도록 0~1초 흩뜨린다 */
const RECONNECT_JITTER_MS = 1000;

export interface SeatBoardState {
  board: SeatBoard | null;
  statusOfSeat: (seatId: number) => SeatStatus;
  isLoading: boolean;
  error: unknown;
  /** SSE가 붙어 있는지 — 끊겨 있으면 화면에 「실시간 연결 끊김」을 알린다 */
  live: boolean;
  reload: () => void;
}

/**
 * BKG-01 스냅샷 + BKG-05 SSE를 합쳐 좌석 상태를 유지한다.
 *
 * 순서가 중요하다: <b>SSE를 먼저 연결해 이벤트를 모으고</b> 스냅샷을 받은 뒤 모아 둔 것을 적용한다.
 * 스냅샷을 먼저 받으면 그 사이에 생긴 변화를 아무도 알려 주지 않는다.
 *
 * <p>좌석 상태를 따로 저장하지 않고 <b>스냅샷 + 받은 이벤트</b>로 매번 계산한다. 그래서
 * 스냅샷이 새로 와도 이벤트를 다시 입히기만 하면 되고, 두 경로가 엇갈려 상태가 틀어질 일이 없다.
 */
export function useSeatBoard(screeningId: number): SeatBoardState {
  const snapshotQuery = useQuery({
    ...seatSnapshotQuery(screeningId),
    enabled: Number.isInteger(screeningId),
  });
  const [events, setEvents] = useState<SeatEvent[]>([]);
  const [live, setLive] = useState(false);

  const refetch = snapshotQuery.refetch;
  /** 이미 스냅샷에 반영된 이벤트를 다시 쌓지 않기 위한 기준 번호 */
  const snapshotSeqRef = useRef(-1);
  const openedRef = useRef(false);

  const snapshotBoard = useMemo(
    () => (snapshotQuery.data ? boardFromSnapshot(snapshotQuery.data) : null),
    [snapshotQuery.data],
  );

  // 스냅샷 + 이벤트 → 지금 좌석 상태. 스냅샷보다 오래된 이벤트는 applySeatEvent가 알아서 버린다
  const applied = useMemo(
    () => (snapshotBoard ? applyBufferedEvents(snapshotBoard, events) : null),
    [snapshotBoard, events],
  );
  const board = applied?.board ?? null;
  const gap = applied?.outcome === "gap";

  useEffect(() => {
    snapshotSeqRef.current = snapshotBoard?.seq ?? -1;
  }, [snapshotBoard]);

  /**
   * 스냅샷을 다시 받는다. 모아 둔 이벤트는 <b>새 스냅샷이 실제로 왔을 때만</b> 정리한다 —
   * 먼저 비우면 재조회가 실패했을 때(네트워크 끊김) 아직 쓸 수 있는 이벤트까지 잃는다.
   */
  const reload = useCallback(() => {
    void refetch().then((result) => {
      const fresh = result.data;
      if (!fresh) return;
      setEvents((current) => current.filter((event) => event.seq > fresh.seq));
    });
  }, [refetch]);

  // 회차가 바뀌면 이전 회차의 이벤트를 버린다
  useEffect(() => {
    snapshotSeqRef.current = -1;
    openedRef.current = false;
    return () => setEvents([]);
  }, [screeningId]);

  // 빈틈이 생기면 200ms 뒤 스냅샷으로 되돌린다 (이벤트로 메우지 않는다)
  useEffect(() => {
    if (!gap) return;
    const timer = window.setTimeout(reload, GAP_REFETCH_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [gap, reload]);

  // SSE 구독. 받은 이벤트는 쌓아 두기만 하고 적용은 위 계산이 한다
  useEffect(() => {
    if (!Number.isInteger(screeningId)) return;
    let reconnectTimer: number | null = null;

    const stop = subscribeSeatEvents(screeningId, {
      onEvent: (event) => {
        if (event.seq <= snapshotSeqRef.current) return;
        setEvents((current) => (current.some((kept) => kept.seq === event.seq) ? current : [...current, event]));
      },
      onOpen: () => {
        setLive(true);
        // 처음 연결은 스냅샷 조회가 이미 돌고 있다. 다시 붙은 것이면 그동안 놓친 변화를 스냅샷으로 메운다
        if (openedRef.current) {
          reconnectTimer = window.setTimeout(reload, Math.random() * RECONNECT_JITTER_MS);
        }
        openedRef.current = true;
      },
      onError: () => setLive(false),
    });

    return () => {
      if (reconnectTimer !== null) window.clearTimeout(reconnectTimer);
      stop();
    };
  }, [screeningId, reload]);

  const statusOfSeat = useCallback(
    (seatId: number): SeatStatus => (board ? statusOf(board, seatId) : "AVAILABLE"),
    [board],
  );

  return {
    board,
    statusOfSeat,
    isLoading: snapshotQuery.isPending,
    // 좌석판이 이미 있으면 배경 재조회 실패를 오류 화면으로 올리지 않는다 —
    // 연결 상태는 live가 알려 주고, 화면은 마지막으로 받은 좌석 상태를 그대로 보여 주는 게 낫다
    error: board ? null : snapshotQuery.error,
    live,
    reload,
  };
}
