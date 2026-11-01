import { useMutation } from "@tanstack/react-query";
import { releaseHold } from "@/entities/booking";
import { ApiError, messageForError } from "@/shared/api";
import { newIdempotencyKey } from "@/shared/lib";

/** 이미 끝나 좌석이 풀린 예매 상태 — 해제하려던 결과와 같다 */
const ALREADY_ENDED = new Set(["EXPIRED", "CANCELED", "PAYMENT_FAILED"]);

/**
 * 해제 요청이 409 `BOOKING_STATE_CONFLICT`인데 예매가 이미 끝나 좌석이 풀린 경우인지.
 * 만료(EXPIRED)·다른 창에서 해제(CANCELED)·결제 거절(PAYMENT_FAILED)이면 해제한 것과 같은 결과다.
 * 결제 진행 중(PENDING + PAYMENT_IN_PROGRESS)·확정(CONFIRMED)은 아니다 — 좌석이 아직 잡혀 있다.
 */
export function isAlreadyReleased(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    error.code === "BOOKING_STATE_CONFLICT" &&
    error.details.some(
      (detail) => detail.field === "status" && typeof detail.value === "string" && ALREADY_ENDED.has(detail.value),
    )
  );
}

/**
 * BKG-03 선점 해제. 상태 전이 자체가 멱등이라 `Idempotency-Key`는 선택이지만,
 * 눌렀는데 응답이 끊긴 경우를 위해 같이 보낸다.
 *
 * @param handlers.onReleased `alreadyEnded`가 true면 누르기 전에 이미 만료·해제돼 있었다
 */
export function useReleaseHold(handlers: {
  onReleased: (result: { alreadyEnded: boolean }) => void;
  onFailed: (message: string) => void;
}) {
  const mutation = useMutation({
    mutationFn: (bookingId: number) => releaseHold(bookingId, newIdempotencyKey()),
    onSuccess: () => handlers.onReleased({ alreadyEnded: false }),
    onError: (error) => {
      if (isAlreadyReleased(error)) {
        handlers.onReleased({ alreadyEnded: true });
        return;
      }
      handlers.onFailed(
        messageForError(error, {
          // 결제가 이미 시작됐거나 확정된 경우 — 화면을 새로 받는 게 맞다
          BOOKING_STATE_CONFLICT: "이미 처리된 예매입니다. 화면을 새로고침해 주세요.",
          NOT_FOUND: "예매를 찾을 수 없습니다.",
        }),
      );
    },
  });

  return { release: mutation.mutate, pending: mutation.isPending };
}
