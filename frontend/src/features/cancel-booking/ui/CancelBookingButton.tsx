import { useState } from "react";
import type { MyBooking } from "@/entities/user";
import { ConfirmDialog, Icon } from "@/shared/ui";
import { useCancelBooking } from "../model/use-cancel-booking";
import styles from "./CancelBookingButton.module.css";

/** 「예매 취소」 글자 버튼 → 확인 대화상자 → BKG-07. 예매 완료 + 상영 전 카드에만 놓는다 (canCancelBooking) */
export function CancelBookingButton({ booking }: { booking: Pick<MyBooking, "bookingId" | "screening" | "seatLabels"> }) {
  const [open, setOpen] = useState(false);
  const cancel = useCancelBooking({ onSuccess: () => setOpen(false) });

  const what = booking.screening
    ? `${booking.screening.movieTitle} · ${booking.seatLabels.join(", ")}`
    : `예매번호 ${booking.bookingId}`;

  return (
    <>
      <button type="button" className={styles.button} onClick={() => setOpen(true)}>
        <Icon name="trash" size={15} />
        예매 취소
      </button>
      <ConfirmDialog
        open={open}
        title="예매를 취소할까요?"
        description={`${what} 예매를 취소하면 좌석이 다시 열리고 결제 금액은 환불돼요. 이 작업은 되돌릴 수 없어요.`}
        confirmLabel="예매 취소하기"
        busyLabel="취소하는 중…"
        busy={cancel.isPending}
        onConfirm={() => cancel.mutate(booking.bookingId)}
        onClose={() => setOpen(false)}
      />
    </>
  );
}
