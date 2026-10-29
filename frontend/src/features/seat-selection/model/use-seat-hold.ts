import { useMutation } from "@tanstack/react-query";
import { holdSeats, type HoldResult } from "@/entities/booking";
import { newIdempotencyKey } from "@/shared/lib";
import { interpretHoldError, type HoldFailure } from "../lib/hold-error";

export interface SeatHoldState {
  hold: (input: { screeningId: number; seatIds: number[] }) => void;
  pending: boolean;
}

/**
 * BKG-02 선점. `Idempotency-Key`는 <b>요청마다 새로</b> 만든다 —
 * 같은 키를 다시 쓰면 서버가 첫 응답을 그대로 돌려주므로(의도된 동작) 새 좌석 선점이 안 된다.
 */
export function useSeatHold(handlers: {
  onHeld: (result: HoldResult) => void;
  onFailed: (failure: HoldFailure) => void;
}): SeatHoldState {
  const mutation = useMutation({
    mutationFn: (input: { screeningId: number; seatIds: number[] }) =>
      holdSeats(input, newIdempotencyKey()),
    onSuccess: handlers.onHeld,
    onError: (error) => handlers.onFailed(interpretHoldError(error)),
  });

  return { hold: mutation.mutate, pending: mutation.isPending };
}
