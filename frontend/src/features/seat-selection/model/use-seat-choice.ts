import { useCallback, useMemo, useRef, useState } from "react";
import type { LayoutSeat } from "@/entities/auditorium";
import { MAX_SEATS } from "@/entities/booking";

export interface SeatChoice {
  seats: LayoutSeat[];
  seatIds: number[];
  has: (seatId: number) => boolean;
  /** 이미 고른 좌석이면 빼고, 아니면 넣는다. 4석을 넘어 못 넣으면 `false` */
  toggle: (seat: LayoutSeat) => boolean;
  clear: () => void;
  full: boolean;
}

/**
 * 고른 좌석 (화면 안에만 있는 상태). 한 번에 최대 4석(ADR-11).
 * 좌석 이름순이 아니라 <b>고른 순서</b>로 둔다 — 지울 때 방금 고른 것을 찾기 쉽다.
 *
 * <p>판단은 ref로 하고 상태는 그 결과를 따라간다. 함수형 업데이터 안에서 받아들였는지를 정하면
 * React가 업데이터를 나중에 실행할 수 있어 <b>거절했는데 `true`를 돌려주는</b> 일이 생긴다
 * (다섯 번째 좌석을 눌렀을 때 안내가 안 뜬다).
 */
export function useSeatChoice(): SeatChoice {
  const [seats, setSeats] = useState<LayoutSeat[]>([]);
  const seatsRef = useRef<LayoutSeat[]>(seats);

  const apply = (next: LayoutSeat[]) => {
    seatsRef.current = next;
    setSeats(next);
  };

  const toggle = useCallback((seat: LayoutSeat) => {
    const current = seatsRef.current;
    const without = current.filter((chosen) => chosen.seatId !== seat.seatId);
    if (without.length !== current.length) {
      apply(without);
      return true;
    }
    if (current.length >= MAX_SEATS) return false;
    apply([...current, seat]);
    return true;
  }, []);

  const clear = useCallback(() => apply([]), []);

  const seatIds = useMemo(() => seats.map((seat) => seat.seatId), [seats]);
  const chosen = useMemo(() => new Set(seatIds), [seatIds]);

  return {
    seats,
    seatIds,
    has: (seatId: number) => chosen.has(seatId),
    toggle,
    clear,
    full: seats.length >= MAX_SEATS,
  };
}
