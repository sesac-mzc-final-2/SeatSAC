import { useMutation } from "@tanstack/react-query";
import { releaseHold } from "@/entities/booking";
import { messageForError } from "@/shared/api";
import { newIdempotencyKey } from "@/shared/lib";

/**
 * BKG-03 선점 해제. 상태 전이 자체가 멱등이라 `Idempotency-Key`는 선택이지만,
 * 눌렀는데 응답이 끊긴 경우를 위해 같이 보낸다.
 */
export function useReleaseHold(handlers: { onReleased: () => void; onFailed: (message: string) => void }) {
  const mutation = useMutation({
    mutationFn: (bookingId: number) => releaseHold(bookingId, newIdempotencyKey()),
    onSuccess: handlers.onReleased,
    onError: (error) =>
      handlers.onFailed(
        messageForError(error, {
          // 이미 결제가 시작됐거나 만료된 경우 — 화면을 새로 받는 게 맞다
          BOOKING_STATE_CONFLICT: "이미 처리된 예매입니다. 화면을 새로고침해 주세요.",
          NOT_FOUND: "예매를 찾을 수 없습니다.",
        }),
      ),
  });

  return { release: mutation.mutate, pending: mutation.isPending };
}
