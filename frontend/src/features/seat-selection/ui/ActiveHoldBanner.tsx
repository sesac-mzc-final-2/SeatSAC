import { useEffect, useState } from "react";
import { Link } from "react-router";
import { serverNow, timeOfIso } from "@/shared/lib";
import { Icon } from "@/shared/ui";
import { ENDING_SOON_MS, holdPhase, type HoldPhase } from "../lib/hold-phase";
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
 *
 * 남은 시간을 매초 보여 주지 않는다 — 문장이 바뀔 때마다 화면 읽기 프로그램이 다시 읽기 때문이다.
 * 「HH:mm 전까지」(분 단위 내림 → 실제 만료보다 항상 이르거나 같다)를 고정으로 두고,
 * 60초 이하·만료처럼 <b>단계가 바뀔 때만</b> 알림(role=status) 문장을 바꾼다.
 */
export function ActiveHoldBanner({ bookingId, expiresAt, payHref, onRelease, releasing }: Props) {
  const phase = useHoldPhase(expiresAt);
  const notice =
    phase === "ending"
      ? "곧 선점 시간이 끝나요. 바로 결제해 주세요."
      : phase === "over"
        ? "선점 시간이 지났어요. 좌석을 다시 골라 주세요."
        : "";

  return (
    <div className={styles.banner}>
      <Icon name="timer" size={19} />
      <p>
        선점한 좌석이 있어요.
        <span className={styles.detail}>
          {expiresAt
            ? ` ${timeOfIso(expiresAt)} 전까지 결제를 완료해 주세요.`
            : // 409 응답에 만료 시각이 없을 수 있다 — 모르는 것을 지어내지 않는다
              " 이어서 결제하거나 선점을 해제해 주세요."}{" "}
          (예매 번호 {bookingId})
        </span>
        <span role="status" className={styles.notice}>
          {notice}
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

/** 단계는 서버 시각에서 매번 계산하고, 바뀌는 두 순간(60초 전·만료)에만 다시 그린다 */
function useHoldPhase(expiresAt: string | null): HoldPhase {
  const [, redraw] = useState(0);
  useEffect(() => {
    if (!expiresAt) return;
    const left = new Date(expiresAt).getTime() - serverNow();
    // 조금 늦게 깨워 경계 바로 앞에서 같은 단계로 다시 그리는 일을 막는다
    const timers = [left - ENDING_SOON_MS, left]
      .filter((ms) => ms > 0)
      .map((ms) => window.setTimeout(() => redraw((count) => count + 1), ms + 50));
    return () => timers.forEach((timer) => window.clearTimeout(timer));
  }, [expiresAt]);
  return holdPhase(expiresAt, serverNow());
}
