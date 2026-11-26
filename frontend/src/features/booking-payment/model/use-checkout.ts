import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { confirmCheckout, getCheckout, startCheckout } from "@/entities/booking";
import { routes } from "@/shared/config";
import { newIdempotencyKey } from "@/shared/lib";
import {
  completeCheckoutFlow,
  resumeCheckoutFlow,
  startCheckoutFlow,
  type CheckoutFlowDeps,
  type CheckoutOutcome,
  type CheckoutStage,
} from "../lib/checkout-flow";
import { openTossPay } from "../lib/portone";

const wait = (ms: number) => new Promise<void>((resolve) => window.setTimeout(resolve, ms));

export interface CheckoutState {
  /** 체크아웃 시작(새 멱등 키) — 쿠폰은 `couponIssueId`, 없으면 null */
  start: (couponIssueId: string | null) => void;
  /** 모바일 결제창이 돌아왔을 때(`?paymentId=…`) 확인 요청 + polling */
  complete: (paymentId: string, closedMessage: string | null) => void;
  /** 진행 중인 체크아웃을 이어서 본다(새로고침·「결과 다시 확인」) */
  resume: () => void;
  pending: boolean;
  stage: CheckoutStage | null;
}

/** 체크아웃 saga 결제(6.2.5, ADR-116) — 기존 결제 화면이 부르는 API만 `/checkout`으로 바꾼다 */
export function useCheckout(bookingId: number, onSettled: (outcome: CheckoutOutcome) => void): CheckoutState {
  const [stage, setStage] = useState<CheckoutStage | null>(null);

  const deps: CheckoutFlowDeps = {
    start: startCheckout,
    get: getCheckout,
    confirm: confirmCheckout,
    open: openTossPay,
    // 모바일은 결제창이 페이지를 떠났다가 이 주소로 돌아온다(포트원이 paymentId·code를 붙인다)
    redirectUrl: `${window.location.origin}${routes.bookingPay(bookingId)}`,
    wait,
    now: () => Date.now(),
    newKey: newIdempotencyKey,
    onStage: setStage,
  };
  const settled = (outcome: CheckoutOutcome) => {
    setStage(null);
    onSettled(outcome);
  };

  const start = useMutation({
    mutationFn: (couponIssueId: string | null) => startCheckoutFlow(bookingId, couponIssueId, deps),
    onSuccess: settled,
  });
  const complete = useMutation({
    mutationFn: ({ paymentId, closedMessage }: { paymentId: string; closedMessage: string | null }) =>
      completeCheckoutFlow(bookingId, paymentId, closedMessage, deps),
    onSuccess: settled,
  });
  const resume = useMutation({
    mutationFn: () => resumeCheckoutFlow(bookingId, deps),
    onSuccess: settled,
  });

  return {
    start: (couponIssueId) => start.mutate(couponIssueId),
    complete: (paymentId, closedMessage) => complete.mutate({ paymentId, closedMessage }),
    resume: () => resume.mutate(),
    pending: start.isPending || complete.isPending || resume.isPending,
    stage,
  };
}
