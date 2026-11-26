import { useMutation } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
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

/** 기다리다 화면을 떠나면 바로 깬다 — 깬 뒤 흐름이 signal을 보고 멈춘다 */
const waitOrAbort = (signal: AbortSignal) => (ms: number) =>
  new Promise<void>((resolve) => {
    const timer = window.setTimeout(done, ms);
    signal.addEventListener("abort", done, { once: true });
    function done() {
      window.clearTimeout(timer);
      signal.removeEventListener("abort", done);
      resolve();
    }
  });

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
  // 화면을 떠나면 polling·결제창 열기를 끊는다(#206 리뷰 Minor 3). 마운트마다 새로 만든다(StrictMode의 두 번 마운트도 맞게)
  const controller = useRef<AbortController>(new AbortController());
  useEffect(() => {
    const current = new AbortController();
    controller.current = current;
    return () => current.abort();
  }, []);

  const flowDeps = (): CheckoutFlowDeps => ({
    start: startCheckout,
    get: getCheckout,
    confirm: confirmCheckout,
    open: openTossPay,
    // 모바일은 결제창이 페이지를 떠났다가 이 주소로 돌아온다(포트원이 paymentId·code를 붙인다)
    redirectUrl: `${window.location.origin}${routes.bookingPay(bookingId)}`,
    wait: waitOrAbort(controller.current.signal),
    now: () => Date.now(),
    newKey: newIdempotencyKey,
    onStage: setStage,
    signal: controller.current.signal,
  });
  const settled = (outcome: CheckoutOutcome) => {
    setStage(null);
    onSettled(outcome);
  };

  const start = useMutation({
    mutationFn: (couponIssueId: string | null) => startCheckoutFlow(bookingId, couponIssueId, flowDeps()),
    onSuccess: settled,
  });
  const complete = useMutation({
    mutationFn: ({ paymentId, closedMessage }: { paymentId: string; closedMessage: string | null }) =>
      completeCheckoutFlow(bookingId, paymentId, closedMessage, flowDeps()),
    onSuccess: settled,
  });
  const resume = useMutation({
    mutationFn: () => resumeCheckoutFlow(bookingId, flowDeps()),
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
