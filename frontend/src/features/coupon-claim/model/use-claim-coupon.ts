import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { claimCoupon, couponKeys } from "@/entities/coupon";
import { newIdempotencyKey } from "@/shared/lib";
import { claimOutcomeOf, type ClaimOutcome } from "../lib/claim-outcome";

/**
 * CPN-02 받기. `Idempotency-Key` 는 누를 때마다 새로 만들되, 결과를 모르는 경우(`retry`)에만 다음 누름에 같은 키를 쓴다 —
 * 503 은 발급이 이미 커밋됐을 수 있어서 새 키로 보내면 「이미 받음」으로 보이거나 재고를 또 깎는다(계약 CPN-02).
 * 끝나면 이벤트(남은 수량·myIssue)와 내 쿠폰을 다시 읽는다.
 */
export function useClaimCoupon(onSettled: (outcome: ClaimOutcome) => void) {
  const queryClient = useQueryClient();
  // 재시도용으로 남겨 둔 키 — 이벤트가 바뀌면 쓰지 않는다
  const pendingKey = useRef<{ eventId: number; key: string } | null>(null);

  const mutation = useMutation({
    mutationFn: async (eventId: number): Promise<ClaimOutcome> => {
      const kept = pendingKey.current;
      const key = kept && kept.eventId === eventId ? kept.key : newIdempotencyKey();
      pendingKey.current = { eventId, key };
      try {
        await claimCoupon(eventId, key);
        return { kind: "issued", already: false };
      } catch (error) {
        return claimOutcomeOf(error);
      }
    },
    onSuccess: (outcome) => {
      if (outcome.kind !== "retry") pendingKey.current = null;
      void queryClient.invalidateQueries({ queryKey: couponKeys.all });
      onSettled(outcome);
    },
  });

  return { claim: (eventId: number) => mutation.mutate(eventId), pending: mutation.isPending };
}
