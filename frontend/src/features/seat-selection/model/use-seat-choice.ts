import { useCallback, useMemo, useState } from "react";
import type { LayoutSeat } from "@/entities/auditorium";
import { MAX_SEATS } from "@/entities/booking";

export interface SeatChoice {
  seats: LayoutSeat[];
  seatIds: number[];
  has: (seatId: number) => boolean;
  /** 이미 고른 좌석이면 빼고, 아니면 넣는다. 4석을 넘으면 `false`를 돌려준다 */
  toggle: (seat: LayoutSeat) => boolean;
  clear: () => void;
  full: boolean;
}

/**
 * 고른 좌석 (화면 안에만 있는 상태). 한 번에 최대 4석(ADR-11).
 * 좌석 이름순이 아니라 <b>고른 순서</b>로 둔다 — 지울 때 방금 고른 것을 찾기 쉽다.
 */
export function useSeatChoice(): SeatChoice {
  const [seats, setSeats] = useState<LayoutSeat[]>([]);

  const toggle = useCallback((seat: LayoutSeat) => {
    let accepted = true;
    setSeats((current) => {
      const without = current.filter((chosen) => chosen.seatId !== seat.seatId);
      if (without.length !== current.length) return without;
      if (current.length >= MAX_SEATS) {
        accepted = false;
        return current;
      }
      return [...current, seat];
    });
    return accepted;
  }, []);

  const seatIds = useMemo(() => seats.map((seat) => seat.seatId), [seats]);
  const chosen = useMemo(() => new Set(seatIds), [seatIds]);

  return {
    seats,
    seatIds,
    has: (seatId: number) => chosen.has(seatId),
    toggle,
    clear: useCallback(() => setSeats([]), []),
    full: seats.length >= MAX_SEATS,
  };
}
