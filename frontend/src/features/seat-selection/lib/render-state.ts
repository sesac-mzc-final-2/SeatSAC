import type { SeatRenderState } from "@/entities/auditorium";
import type { SeatStatus } from "@/entities/booking";

/**
 * 좌석 점유 상태(booking) + 내가 고른 좌석 → 배치도에 그릴 상태(auditorium).
 * 두 엔티티를 아는 자리는 feature라 변환도 여기서 한다.
 *
 * 내가 선점한 좌석도 서버에서는 `HELD`로 오기 때문에, 내가 고른 좌석인지를 먼저 본다 —
 * 안 그러면 방금 고른 좌석이 「다른 사람이 선점 중」으로 보인다.
 */
export function seatRenderState(options: {
  status: SeatStatus;
  active: boolean;
  selected: boolean;
  /** 내 예매가 점유한 좌석 (선점 후 결제 화면·새로고침) */
  mine?: boolean;
}): SeatRenderState {
  if (!options.active) return "inactive";
  if (options.selected || options.mine) return "selected";
  if (options.status === "HELD") return "held";
  if (options.status === "RESERVED") return "reserved";
  return "available";
}
