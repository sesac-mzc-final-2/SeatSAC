import { useMutation } from "@tanstack/react-query";
import { useRef } from "react";
import { holdSeats, type HoldResult } from "@/entities/booking";
import { newIdempotencyKey } from "@/shared/lib";
import { interpretHoldError, type HoldFailure } from "../lib/hold-error";
import { holdRequestSignature, shouldReuseHoldKey } from "../lib/hold-key";

export interface SeatHoldState {
  hold: (input: { screeningId: number; seatIds: number[] }) => void;
  pending: boolean;
}

/**
 * BKG-02 선점. `Idempotency-Key`는 <b>결과를 모를 때 같은 좌석으로 다시 보내는 경우에만</b> 그대로 쓰고,
 * 그 밖에는 새로 만든다(docs/conventions.md 6장, {@link shouldReuseHoldKey}).
 * 응답이 끊긴 뒤 다시 누르면 같은 키로 첫 결과(201)를 돌려받아 결제로 이어진다.
 */
export function useSeatHold(handlers: {
  onHeld: (result: HoldResult) => void;
  onFailed: (failure: HoldFailure) => void;
}): SeatHoldState {
  // 결과를 모르는 마지막 요청 — 같은 요청을 다시 보낼 때만 키를 이어 쓴다
  const unsettled = useRef<{ signature: string; key: string } | null>(null);

  const mutation = useMutation({
    mutationFn: (input: { screeningId: number; seatIds: number[] }) => {
      const signature = holdRequestSignature(input);
      const key = unsettled.current?.signature === signature ? unsettled.current.key : newIdempotencyKey();
      unsettled.current = { signature, key };
      return holdSeats(input, key);
    },
    onSuccess: (result) => {
      unsettled.current = null;
      handlers.onHeld(result);
    },
    onError: (error) => {
      if (!shouldReuseHoldKey(error)) unsettled.current = null;
      handlers.onFailed(interpretHoldError(error));
    },
  });

  return { hold: mutation.mutate, pending: mutation.isPending };
}
