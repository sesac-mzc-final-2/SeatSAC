import { useQuery } from "@tanstack/react-query";
import { opsCouponEventsQuery, type CouponEvent, type CouponEventState } from "@/entities/admin";
import { CouponEventForm } from "@/features/ops-coupon-event-create";
import { isApiError } from "@/shared/api";
import { dateOfIso, formatDateKo, timeOfIso, useDocumentTitle } from "@/shared/lib";
import { Badge, EmptyState, ErrorState, LoadingBlock, type BadgeTone } from "@/shared/ui";
import { AdminForbidden } from "@/widgets/admin-layout";
import styles from "./AdminPages.module.css";

/** 진행 중 이벤트의 발급 수가 빨리 바뀌어 5초마다 다시 본다 */
const POLL_INTERVAL_MS = 5_000;

const LIST_MESSAGES: Record<string, string> = {
  SERVICE_UNAVAILABLE: "쿠폰 서버(coupon)를 부르지 못했어요. 잠시 후 다시 시도해 주세요.",
};

const STATE_META: Record<CouponEventState, { label: string; tone: BadgeTone }> = {
  UPCOMING: { label: "예정", tone: "neutral" },
  OPEN: { label: "진행 중", tone: "accent" },
  CLOSED: { label: "종료", tone: "muted" },
};

function period(event: CouponEvent): string {
  const startDate = dateOfIso(event.startsAt);
  const endDate = dateOfIso(event.endsAt);
  const end = startDate === endDate ? timeOfIso(event.endsAt) : `${formatDateKo(endDate)} ${timeOfIso(event.endsAt)}`;
  return `${formatDateKo(startDate)} ${timeOfIso(event.startsAt)} ~ ${end}`;
}

/**
 * 관리자 「쿠폰 이벤트」 `/admin/coupons` (6.3.9, OPS-24): VIP 선착순 이벤트 만들기 + 최근 이벤트 목록(발급·남은 수량).
 * - 검증(시작이 지났는지·기간 겹침)은 coupon이 한다. 진행 중이거나 예정인 이벤트와 기간이 겹치면 만들 수 없다
 * - 「발급」은 DB 발급 수(정확), 「남은 수량」은 Valkey 재고 키(표시용)
 * - 5초마다 다시 본다. 다시 받다 실패해도 마지막 목록은 그대로 둔다
 */
export function AdminCouponsPage() {
  useDocumentTitle("쿠폰 이벤트 · 관리자");
  const events = useQuery({ ...opsCouponEventsQuery(), refetchInterval: POLL_INTERVAL_MS });

  const header = (
    <div className={styles.headRow}>
      <h2 className={styles.title}>쿠폰 이벤트</h2>
      <p className="micro">VIP 선착순 할인 쿠폰 · 시각은 KST</p>
    </div>
  );

  if (events.isPending) return <LoadingBlock label="쿠폰 이벤트를 불러오는 중이에요" />;
  if (events.isError && !events.data) {
    if (isApiError(events.error, 403)) return <AdminForbidden />;
    return (
      <ErrorState
        error={events.error}
        title="쿠폰 이벤트를 불러오지 못했어요"
        messages={LIST_MESSAGES}
        onRetry={() => void events.refetch()}
      />
    );
  }

  const { items } = events.data;

  return (
    <div className="panel">
      {header}
      <CouponEventForm />

      {events.isError && (
        <p className={styles.notice} role="alert">
          목록을 새로 받지 못했어요 — 마지막으로 받은 목록이에요. 5초 뒤 다시 시도해요.
        </p>
      )}

      <h3 className={styles.subTitle}>최근 이벤트</h3>
      {items.length === 0 ? (
        <EmptyState as="h3" icon="ticket" title="아직 이벤트가 없어요" description="위에서 첫 이벤트를 만들어 주세요." />
      ) : (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <caption className="sr-only">쿠폰 이벤트 목록</caption>
            <thead>
              <tr>
                <th scope="col">이벤트</th>
                <th scope="col">상태</th>
                <th scope="col">할인</th>
                <th scope="col">기간</th>
                <th scope="col">발급/수량</th>
                <th scope="col">남은 수량</th>
              </tr>
            </thead>
            <tbody>
              {items.map((event) => {
                const meta = STATE_META[event.state] ?? { label: event.state, tone: "neutral" as const };
                return (
                  <tr key={event.eventId}>
                    <th scope="row">
                      <span className={styles.serviceName}>{event.title}</span>
                      <span className="micro"> #{event.eventId} · {event.eligibleGrade}</span>
                    </th>
                    <td>
                      <Badge tone={meta.tone}>{meta.label}</Badge>
                    </td>
                    <td>{event.discountRate}%</td>
                    <td className="micro">{period(event)}</td>
                    <td>
                      {event.issuedQuantity.toLocaleString("ko-KR")}/{event.totalQuantity.toLocaleString("ko-KR")}
                    </td>
                    <td>{event.remainingQuantity.toLocaleString("ko-KR")}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
