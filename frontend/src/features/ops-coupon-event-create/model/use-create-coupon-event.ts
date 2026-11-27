import { useMutation, useQueryClient } from "@tanstack/react-query";
import { createCouponEvent, opsKeys, type CouponEventCreateRequest } from "@/entities/admin";
import { ApiError, messageForError } from "@/shared/api";
import { useToast } from "@/shared/ui";

/** coupon 검증 사유(`details[].reason`) → 관리자 문구 */
const REASON_MESSAGES: Record<string, string> = {
  STARTS_IN_PAST: "시작 시각이 이미 지났어요. 지금 이후로 고르거나 「지금 시작」을 눌러 주세요.",
  EVENT_OVERLAP: "아직 끝나지 않은 다른 이벤트와 기간이 겹쳐요. 그 이벤트가 끝난 뒤로 시작 시각을 옮겨 주세요.",
};

const CODE_MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: "입력 값이 올바르지 않아요. 할인율 1~99%, 수량 1~10,000, 진행 시간 1~1,440분인지 확인해 주세요.",
  FORBIDDEN: "관리자(ADMIN)만 쿠폰 이벤트를 만들 수 있어요.",
};

/**
 * 응답을 끝까지 받지 못했으면 **만들어졌는지 불확실**하다 — 목록을 다시 받아 확인하게 한다.
 * - 503: coupon이 커밋한 뒤 응답만 잃었을 수 있다(계약 — ops-api는 200·201·400 밖을 503으로 준다)
 * - 그 밖의 5xx(CloudFront 502·504 등)·응답 전에 끊긴 네트워크 오류(`NetworkError` 등 `ApiError`가 아닌 오류)도 같다
 * `Idempotency-Key`가 없어 같은 요청을 그냥 다시 보내면 안 된다(이미 만들어졌으면 EVENT_OVERLAP으로 막히긴 한다)
 */
export const UNCERTAIN_MESSAGE =
  "만들어졌는지 확실하지 않아요(서버 응답을 받지 못했어요). 아래 목록에 새 이벤트가 있는지 확인하고, 없을 때만 다시 만들어 주세요.";

export function createErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError) || error.status >= 500) return UNCERTAIN_MESSAGE;
  const reason = error.details.find((detail) => detail.reason && REASON_MESSAGES[detail.reason])?.reason;
  if (reason) return REASON_MESSAGES[reason]!;
  return messageForError(error, CODE_MESSAGES);
}

/** OPS-24 쿠폰 이벤트 만들기. 성공·실패 모두 목록을 다시 받는다(503이면 만들어졌는지 목록으로 확인) */
export function useCreateCouponEvent(options: { onSuccess?: () => void } = {}) {
  const queryClient = useQueryClient();
  const toast = useToast();

  return useMutation({
    mutationFn: (body: CouponEventCreateRequest) => createCouponEvent(body),
    onSuccess: (event) => {
      toast.show(`「${event.title}」 이벤트를 만들었어요.`);
      options.onSuccess?.();
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: opsKeys.couponEvents() }),
  });
}
