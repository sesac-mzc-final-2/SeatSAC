import { useEffect, useState } from "react";
import { Link } from "react-router";
import { secondsLeft } from "@/entities/booking";
import { Icon } from "@/shared/ui";
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
 * 409 ACTIVE_HOLD_EXISTS — 이 회차에 진행 중인 선점이 이미 있을 때 (시안 상단 배너).
 * 한 회차에 선점은 하나라서, 이어서 결제하거나 먼저 풀어야 새로 고를 수 있다.
 */
export function ActiveHoldBanner({ bookingId, expiresAt, payHref, onRelease, releasing }: Props) {
  const left = useSecondsLeft(expiresAt);
  return (
    <div className={styles.banner} role="status">
      <Icon name="timer" size={19} />
      <p>
        선점한 좌석이 있어요.
        <span>
          {expiresAt === null
            ? // 409 응답에 만료 시각이 없을 수 있다 — 모르는 것을 「거의 끝났다」고 하지 않는다
              " 이어서 결제하거나 선점을 해제해 주세요."
            : left > 0
              ? ` ${Math.floor(left / 60)}분 ${String(left % 60).padStart(2, "0")}초 안에 결제를 완료해 주세요.`
              : " 선점 시간이 거의 끝났어요."}{" "}
          (예매 번호 {bookingId})
        </span>
      </p>
      <Link className={styles.action} to={payHref}>
        이어서 결제
        <Icon name="arrow-right" size={14} />
      </Link>
      <button type="button" className={styles.release} onClick={onRelease} disabled={releasing}>
        {releasing ? "해제 중…" : "선점 해제"}
      </button>
    </div>
  );
}

/** 만료 시각에서 매번 계산하고 1초마다 다시 그린다 (탭이 멈췄다 돌아와도 어긋나지 않게) */
function useSecondsLeft(expiresAt: string | null): number {
  const [, redraw] = useState(0);
  useEffect(() => {
    if (!expiresAt) return;
    const timer = window.setInterval(() => redraw((count) => count + 1), 1000);
    return () => window.clearInterval(timer);
  }, [expiresAt]);
  return expiresAt ? secondsLeft(expiresAt) : 0;
}
