import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { getBooking, payBooking, type PayRequest } from "@/entities/booking";
import { newIdempotencyKey } from "@/shared/lib";
import { settlePayment, type PayOutcome } from "../lib/pay-flow";

const wait = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms));

export interface PayBookingState {
  pay: (body: PayRequest) => void;
  pending: boolean;
  /** 503 뒤 결과를 확인하는 중이면 몇 번째인지 (아니면 0) */
  checking: number;
}

/**
 * BKG-10 결제 한 번 + 결과 확정. 실패해도 재시도 버튼을 두지 않는다 — 예매당 결제는 1회다(ADR-24).
 * 결과는 {@link PayOutcome} 한 가지로 끝나고, 화면은 그걸로 결과 패널을 고른다.
 */
export function usePayBooking(bookingId: number, onSettled: (outcome: PayOutcome) => void): PayBookingState {
  const [checking, setChecking] = useState(0);

  const mutation = useMutation({
    mutationFn: (body: PayRequest) =>
      settlePayment(bookingId, body, {
        pay: payBooking,
        load: getBooking,
        wait,
        newKey: newIdempotencyKey,
        onChecking: setChecking,
      }),
    onSuccess: (outcome) => {
      setChecking(0);
      onSettled(outcome);
    },
  });

  return { pay: mutation.mutate, pending: mutation.isPending, checking };
}
