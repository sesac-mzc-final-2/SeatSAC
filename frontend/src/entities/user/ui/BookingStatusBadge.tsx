import { bookingStatusLabel, bookingStatusTone } from "../lib/booking-status";
import type { MyBookingStatus } from "../model/types";
import styles from "./BookingStatusBadge.module.css";

/** 상태 배지 4종: 예매 완료(포인트 컬러) / 결제 실패·시간 초과(회색) / 취소됨(취소선) */
export function BookingStatusBadge({ status }: { status: MyBookingStatus }) {
  return (
    <span className={styles.badge} data-tone={bookingStatusTone(status)}>
      {bookingStatusLabel(status)}
    </span>
  );
}
