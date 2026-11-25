import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
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
  const event = eventQuery.data;
  // 등급은 이벤트가 있을 때만 읽는다(홈이 늘 /users/me 를 부르지 않게). 실패해도 토스트 없이 — 판정은 claim 이 한다
  const profileQuery = useQuery({ ...myProfileQuery(), enabled: authenticated && !!event, meta: { silentError: true } });
  // 받기는 배너를 숨기는 분기보다 위에 둔다 — 결과를 모른 채(503) 재조회도 실패해 배너가 잠시 숨어도
  // 재시도용 Idempotency-Key 가 사라지지 않고, 돌아온 뒤 같은 키로 다시 보낸다
  const toast = useToast();
  const { claim, pending } = useClaimCoupon((outcome) => {
    if (outcome.kind === "issued") {
      toast.show(outcome.already ? "이미 받은 쿠폰이에요. 결제할 때 쓸 수 있어요." : "쿠폰을 받았어요! 결제할 때 쓸 수 있어요.");
    } else {
      toast.show(outcome.message, { tone: "error" });
    }
  });
  const refetchEvent = eventQuery.refetch;
  const onBoundary = useCallback(() => void refetchEvent(), [refetchEvent]);

  // 조회 실패(503 등)는 메인 화면을 막지 않게 조용히 숨긴다 — 이전에 받은 값(남은 수량·시각)이 남아 있어도 숨기고,
  // 30초 뒤 다시 읽어 성공하면 돌아온다
  if (!authenticated || !event || eventQuery.isError) return null;
  return (
    <Banner
      event={event}
      eligibility={
        profileQuery.isPending
          ? "checking"
          : profileQuery.isError || isVip(profileQuery.data)
            ? "eligible"
            : "not-eligible"
      }
      showMyCouponsLink={showMyCouponsLink}
      checkedAt={eventQuery.dataUpdatedAt}
      onBoundary={onBoundary}
      onClaim={claim}
      claiming={pending}
    />
  );
}

/**
 * 받기 버튼을 열지: 등급을 읽는 중이면 중립 비활성(VIP 에게 「VIP 전용」이 깜박이지 않게), NORMAL 이면 「VIP 전용」.
 * 등급을 못 읽었으면(user 503·토큰 거절) 열어 두고 claim 의 403 에 맡긴다 — VIP 가 못 받는 쪽으로 틀리지 않게
 */
type Eligibility = "checking" | "eligible" | "not-eligible";

/** 경계 재조회 횟수·간격 — 화면 시계는 서버와 ±0.5초 어긋날 수 있어 아직 바뀌기 전 응답을 받을 수 있다 */
const BOUNDARY_TRIES = 3;
const BOUNDARY_RETRY_MS = 1500;

interface BannerProps {
  event: CurrentCouponEvent;
  eligibility: Eligibility;
  /** 이벤트를 마지막으로 받은 시각 — 같은 값이 다시 와도(구조 공유로 같은 객체) 경계 재조회를 이어 가게 */
  checkedAt: number;
  /** 마이페이지 쿠폰 탭 안에서는 「내 쿠폰 보기」 링크를 뺀다 */
  showMyCouponsLink: boolean;
  onBoundary: () => void;
  onClaim: (eventId: number) => void;
  claiming: boolean;
}

function Banner({ event, eligibility, checkedAt, showMyCouponsLink, onBoundary, onClaim, claiming }: BannerProps) {
  const now = useTicker(1000);
  const phase = couponEventPhase(event, now);
  const target = couponCountdownTarget(event, phase);
  const secondsLeft = target ? Math.max(0, Math.ceil((new Date(target).getTime() - now) / 1000)) : 0;

  // 화면 시계로 단계가 넘어가면 서버 판단을 다시 받는다(시작·종료). 받은 응답이 아직 이전 단계면 1.5초 뒤 다시, 최대 3번
  const boundaryTries = useRef<{ key: string; count: number } | null>(null);
  useEffect(() => {
    if (phase === event.state) return;
    const key = `${event.eventId}:${phase}`;
    const count = boundaryTries.current?.key === key ? boundaryTries.current.count : 0;
    if (count >= BOUNDARY_TRIES) return;
    const timer = window.setTimeout(
      () => {
        boundaryTries.current = { key, count: count + 1 };
        onBoundary();
      },
      count === 0 ? 0 : BOUNDARY_RETRY_MS,
    );
    return () => window.clearTimeout(timer);
  }, [event.eventId, event.state, phase, checkedAt, onBoundary]);


  const claimed = event.myIssue !== null;
  const soldOut = event.remainingQuantity <= 0;
  const remainRatio = Math.min(1, Math.max(0, event.remainingQuantity / event.totalQuantity));

  return (
    <section className={styles.banner} aria-labelledby="coupon-event-title" data-phase={phase}>
      <div className={styles.rate}>
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
        ) : eligibility === "checking" ? (
          <Button disabled>
            <Icon name="loader" size={16} className={styles.spin} /> 확인 중
          </Button>
        ) : eligibility === "not-eligible" ? (
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
          <Button variant="primary" onClick={() => onClaim(event.eventId)} disabled={claiming}>
            {claiming ? (
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
