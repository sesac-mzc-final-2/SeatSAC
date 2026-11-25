import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import {
  couponCountdownTarget,
  couponEventPhase,
  currentCouponEventQuery,
  formatLongCountdown,
  type CurrentCouponEvent,
} from "@/entities/coupon";
import { useSession } from "@/entities/session";
import { isVip, myProfileQuery, VipBadge } from "@/entities/user";
import { routes } from "@/shared/config";
import { serverNow } from "@/shared/lib";
import { Button, Icon, useToast } from "@/shared/ui";
import { useClaimCoupon } from "../model/use-claim-coupon";
import styles from "./CouponEventBanner.module.css";

/**
 * VIP 선착순 쿠폰 배너 (CPN-01·02, 6.3.6): 할인율 · 남은 수량 · 카운트다운(시작 전 → 시작까지, 진행 중 → 종료까지) · 받기.
 * 로그인한 사용자에게만(API 가 인증 필요), 보여 줄 이벤트가 없으면(404) 아무것도 그리지 않는다.
 * NORMAL 사용자도 이벤트는 보이고 「VIP 전용」 안내만 — 최종 판정은 claim 이 한다.
 */
export function CouponEventBanner({ showMyCouponsLink = true }: { showMyCouponsLink?: boolean }) {
  const session = useSession();
  const authenticated = session.status === "authenticated";
  const eventQuery = useQuery({ ...currentCouponEventQuery(), enabled: authenticated });
  const profileQuery = useQuery({ ...myProfileQuery(), enabled: authenticated });

  const event = eventQuery.data;
  // 조회 실패(503 등)는 메인 화면을 막지 않게 조용히 숨긴다 — 30초 뒤 다시 읽는다
  if (!authenticated || !event) return null;
  return (
    <Banner
      event={event}
      vip={isVip(profileQuery.data)}
      showMyCouponsLink={showMyCouponsLink}
      onBoundary={() => void eventQuery.refetch()}
    />
  );
}

interface BannerProps {
  event: CurrentCouponEvent;
  vip: boolean;
  /** 마이페이지 쿠폰 탭 안에서는 「내 쿠폰 보기」 링크를 뺀다 */
  showMyCouponsLink: boolean;
  onBoundary: () => void;
}

function Banner({ event, vip, showMyCouponsLink, onBoundary }: BannerProps) {
  const toast = useToast();
  const now = useTicker(1000);
  const phase = couponEventPhase(event, now);
  const target = couponCountdownTarget(event, phase);
  const secondsLeft = target ? Math.max(0, Math.ceil((new Date(target).getTime() - now) / 1000)) : 0;

  // 화면 시계로 단계가 넘어가면 그 순간 한 번 서버 판단을 다시 받는다 (시작·종료)
  const boundaryFor = useRef<string | null>(null);
  useEffect(() => {
    const key = `${event.eventId}:${phase}`;
    if (phase !== event.state && boundaryFor.current !== key) {
      boundaryFor.current = key;
      onBoundary();
    }
  }, [event.eventId, event.state, phase, onBoundary]);

  const { claim, pending } = useClaimCoupon((outcome) => {
    if (outcome.kind === "issued") {
      toast.show(outcome.already ? "이미 받은 쿠폰이에요. 결제할 때 쓸 수 있어요." : "쿠폰을 받았어요! 결제할 때 쓸 수 있어요.");
    } else {
      toast.show(outcome.message, { tone: "error" });
    }
  });

  const claimed = event.myIssue !== null;
  const soldOut = event.remainingQuantity <= 0;
  const remainRatio = Math.min(1, Math.max(0, event.remainingQuantity / event.totalQuantity));

  return (
    <section className={styles.banner} aria-labelledby="coupon-event-title" data-phase={phase}>
      <div className={styles.rate} aria-hidden="true">
        <strong>{event.discountRate}%</strong>
        <span>OFF</span>
      </div>

      <div className={styles.copy}>
        <p className={styles.eyebrow}>
          {event.eligibleGrade === "VIP" ? <VipBadge size="sm" /> : null}
          <span>{phase === "UPCOMING" ? "선착순 쿠폰 오픈 예정" : phase === "OPEN" ? "선착순 쿠폰 진행 중" : "이벤트 종료"}</span>
        </p>
        <h2 id="coupon-event-title">{event.title}</h2>
        <div className={styles.meta}>
          {target && (
            <span className={styles.countdown} role="timer" aria-live="off">
              <Icon name="timer" size={15} />
              {phase === "UPCOMING" ? "시작까지" : "종료까지"} <b>{formatLongCountdown(secondsLeft)}</b>
            </span>
          )}
          <span className={styles.stock}>
            남은 수량 <b>{event.remainingQuantity.toLocaleString("ko-KR")}</b> / {event.totalQuantity.toLocaleString("ko-KR")}
          </span>
        </div>
        <div
          className={styles.bar}
          role="progressbar"
          aria-label="남은 쿠폰 수량"
          aria-valuemin={0}
          aria-valuemax={event.totalQuantity}
          aria-valuenow={event.remainingQuantity}
        >
          <span style={{ width: `${remainRatio * 100}%` }} />
        </div>
      </div>

      <div className={styles.action}>
        {claimed ? (
          <>
            <span className={styles.done}>
              <Icon name="check" size={16} /> 받은 쿠폰이에요
            </span>
            {showMyCouponsLink && (
              <Link to={routes.myPage("coupons")} className={styles.link}>
                내 쿠폰 보기
              </Link>
            )}
          </>
        ) : !vip ? (
          <>
            <Button disabled>VIP 전용</Button>
            <span className={styles.hint}>VIP 회원만 받을 수 있어요</span>
          </>
        ) : phase === "UPCOMING" ? (
          <Button disabled>
            <Icon name="clock" size={16} /> 곧 시작해요
          </Button>
        ) : phase === "CLOSED" ? (
          <Button disabled>종료됐어요</Button>
        ) : soldOut ? (
          <Button disabled>모두 소진됐어요</Button>
        ) : (
          <Button variant="primary" onClick={() => claim(event.eventId)} disabled={pending}>
            {pending ? (
              <>
                <Icon name="loader" size={16} className={styles.spin} /> 받는 중…
              </>
            ) : (
              <>
                쿠폰 받기 <Icon name="arrow-right" size={16} />
              </>
            )}
          </Button>
        )}
      </div>
    </section>
  );
}

/** 서버 기준 지금 시각을 주기마다 다시 그린다 (남은 시간은 매번 시각에서 계산 — 탭이 멈췄다 와도 맞다) */
function useTicker(intervalMs: number): number {
  const [now, setNow] = useState(serverNow);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(serverNow()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}
