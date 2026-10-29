import { formatCountdown, secondsLeft } from "@/entities/booking";
import { Button, ButtonLink, Icon } from "@/shared/ui";
import styles from "./ActiveHoldBanner.module.css";

interface Props {
  bookingId: number;
  /** 서버가 알려 준 선점 만료 시각 (없을 수도 있다) */
  expiresAt: string | null;
  payHref: string;
  onRelease: () => void;
  releasing: boolean;
}

/**
 * 409 ACTIVE_HOLD_EXISTS — 이 회차에 진행 중인 선점이 이미 있을 때.
 * 한 회차에 선점은 하나라서, 이어서 결제하거나 먼저 풀어야 새로 고를 수 있다.
 */
export function ActiveHoldBanner({ bookingId, expiresAt, payHref, onRelease, releasing }: Props) {
  const left = expiresAt ? secondsLeft(expiresAt) : 0;
  return (
    <div className={styles.banner} role="status">
      <Icon name="alert-circle" size={18} />
      <div className={styles.text}>
        <strong>이미 선택해 둔 좌석이 있습니다.</strong>
        <span>
          예매 번호 {bookingId}
          {left > 0 && ` · 남은 시간 ${formatCountdown(left)}`}
        </span>
      </div>
      <div className={styles.actions}>
        <ButtonLink to={payHref} variant="primary" size="sm">
          이어서 결제
        </ButtonLink>
        <Button size="sm" onClick={onRelease} disabled={releasing}>
          {releasing ? "해제 중…" : "선택 취소"}
        </Button>
      </div>
    </div>
  );
}
