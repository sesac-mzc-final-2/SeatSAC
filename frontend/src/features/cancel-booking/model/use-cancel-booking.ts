import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { userKeys } from "@/entities/user";
import { messageForError } from "@/shared/api";
import { useToast } from "@/shared/ui";
import { cancelBooking } from "../api/cancel-booking-api";

/** BKG-07 오류 코드 → 사용자 문구 (공통 코드 외) */
export const CANCEL_MESSAGES: Record<string, string> = {
  SCREENING_STARTED: "이미 상영이 시작된 예매는 취소할 수 없어요.",
  BOOKING_STATE_CONFLICT: "이미 취소되었거나 취소할 수 없는 상태의 예매예요.",
  FORBIDDEN: "내 예매만 취소할 수 있어요.",
  NOT_FOUND: "예매를 찾을 수 없어요. 목록을 다시 불러옵니다.",
  SERVICE_UNAVAILABLE: "결제 취소가 잠시 지연되고 있어요. 잠시 후 다시 시도해 주세요.",
};

/**
 * 예매 취소 뮤테이션. 성공·실패 모두 내역을 다시 받는다(다른 경로로 이미 바뀐 상태를 맞추기 위해).
 * 같은 예매의 재시도는 같은 Idempotency-Key 를 쓴다
 */
export function useCancelBooking(options: { onSuccess?: () => void } = {}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const keys = useRef(new Map<number, string>());

  const keyFor = (bookingId: number): string => {
    const existing = keys.current.get(bookingId);
    if (existing) return existing;
    const created = crypto.randomUUID();
    keys.current.set(bookingId, created);
    return created;
  };

  return useMutation({
    mutationFn: (bookingId: number) => cancelBooking(bookingId, keyFor(bookingId)),
    onSuccess: (_result, bookingId) => {
      keys.current.delete(bookingId);
      toast.show("예매를 취소했어요. 좌석이 다시 열렸습니다.");
      options.onSuccess?.();
    },
    onError: (error) => {
      toast.show(messageForError(error, CANCEL_MESSAGES), { tone: "error" });
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: userKeys.all }),
  });
}
