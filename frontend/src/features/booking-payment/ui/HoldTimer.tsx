import { formatCountdown } from "@/entities/booking";
import { Icon } from "@/shared/ui";
import styles from "./HoldTimer.module.css";

/** W5 상단 카운트다운 (시안) — 1분 이하면 포인트 색으로 깜빡인다 */
export function HoldTimer({ secondsLeft }: { secondsLeft: number }) {
  const urgent = secondsLeft <= 60;
  return (
    <div
      className={styles.timer}
      data-urgent={urgent || undefined}
      role="timer"
      aria-label={`선점 남은 시간 ${secondsLeft}초`}
    >
      <div>
        <Icon name="timer" size={22} />
        <div>
          <strong>선점한 자리를 지켜드리고 있어요</strong>
          <p>시간 안에 결제를 완료해 주세요. 시간이 지나면 좌석이 해제돼요.</p>
        </div>
      </div>
      <span>{secondsLeft > 0 ? formatCountdown(secondsLeft) : "0:00"}</span>
    </div>
  );
}
