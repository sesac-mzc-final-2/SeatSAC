import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { getBooking, payBooking, preparePayment } from "@/entities/booking";
import { routes } from "@/shared/config";
import { newIdempotencyKey } from "@/shared/lib";
import type { PayOutcome } from "../lib/pay-flow";
import { openTossPay } from "../lib/portone";
import { completeTossPay, startTossPay, type TossPayStage } from "../lib/tosspay-flow";

const wait = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms));

export interface TossPayState {
  /** ① 준비 → ② 결제창 → ③ 완료 확인 */
  start: () => void;
  /** 모바일 결제창이 돌아왔을 때(`?paymentId=…`) ③만 */
  complete: (paymentId: string, closedMessage: string | null) => void;
  pending: boolean;
  stage: TossPayStage | null;
  /** 503 뒤 결과를 확인하는 중이면 몇 번째인지 (아니면 0) */
  checking: number;
}

/** 토스페이 결제 한 번 + 결과 확정. 결제는 예매당 1회라 실패해도 다시 결제하는 버튼을 두지 않는다(ADR-24) */
export function useTossPay(bookingId: number, onSettled: (outcome: PayOutcome) => void): TossPayState {
  const [stage, setStage] = useState<TossPayStage | null>(null);
  const [checking, setChecking] = useState(0);

  const base = {
    pay: payBooking,
    load: getBooking,
    wait,
    newKey: newIdempotencyKey,
    onChecking: setChecking,
    onStage: setStage,
  };
  const settled = (outcome: PayOutcome) => {
    setStage(null);
    setChecking(0);
    onSettled(outcome);
  };

  const start = useMutation({
    mutationFn: () =>
      startTossPay(bookingId, {
        ...base,
        prepare: preparePayment,
        open: openTossPay,
        // 모바일은 결제창이 페이지를 떠났다가 이 주소로 돌아온다(포트원이 paymentId·code를 붙인다)
        redirectUrl: `${window.location.origin}${routes.bookingPay(bookingId)}`,
      }),
    onSuccess: settled,
  });
  const complete = useMutation({
    mutationFn: ({ paymentId, closedMessage }: { paymentId: string; closedMessage: string | null }) =>
      completeTossPay(bookingId, paymentId, closedMessage, base),
    onSuccess: settled,
  });

  return {
    start: () => start.mutate(),
    complete: (paymentId, closedMessage) => complete.mutate({ paymentId, closedMessage }),
    pending: start.isPending || complete.isPending,
    stage,
    checking,
  };
}
