import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { opsUnmetDemandQuery, type UnmetDemandCategoryCount } from "@/entities/admin";
import { isApiError } from "@/shared/api";
import { addDays, formatDateKo, useDocumentTitle, useTodayKst } from "@/shared/lib";
import { Badge, EmptyState, ErrorState, LoadingBlock, SegmentedTabs } from "@/shared/ui";
import { AdminForbidden } from "@/widgets/admin-layout";
import styles from "./AdminPages.module.css";

/** 지표 `ai_unmet_demand_total{category}`과 같은 값. 모르는 값은 글자 그대로 보인다(계약이 enum으로 두지 않음) */
const CATEGORY_LABELS: Record<string, { label: string; hint: string }> = {
  action_request: { label: "기능 실행 요청", hint: "예매 취소·결제처럼 채팅이 하지 않는 일을 해 달라는 요청" },
  site_feature: { label: "사이트 이용 문의", hint: "사이트 기능·이용 방법을 묻는 질문" },
  recommendation: { label: "영화 추천", hint: "조건 없이 볼 만한 영화를 골라 달라는 요청" },
  unrelated: { label: "서비스와 무관", hint: "영화 예매와 관계없는 질문" },
  unknown: { label: "분류 안 됨", hint: "정해진 분류에 들지 않은 질문" },
};

const categoryLabel = (category: string) => CATEGORY_LABELS[category]?.label ?? category;

type Period = "7" | "14" | "30";
const PERIODS = [
  { value: "7", label: "최근 7일" },
  { value: "14", label: "최근 14일" },
  { value: "30", label: "최근 30일" },
] as const satisfies ReadonlyArray<{ value: Period; label: string }>;

const TOP_LIMIT = 10;

const STATS_MESSAGES: Record<string, string> = {
  SERVICE_UNAVAILABLE: "AI 서버(ai)에서 통계를 받지 못했어요. ai 노드가 꺼져 있거나 ai_db를 쓰지 않는 중이면 통계가 없어요.",
  VALIDATION_FAILED: "기간이 올바르지 않아요(최대 31일). 다른 기간을 골라 주세요.",
};

/**
 * 관리자 「AI 범위 밖 질문」 `/admin/ai-demand` (6.3.7, OPS-23): 챗봇이 답하지 못한 질문의 분류별 막대 + 많이 나온 요약.
 * 질문 원문은 없다 — 분류·Claude가 쓴 요약·횟수만(ADR-120). 기간은 오늘(KST)까지 7·14·30일(계약 상한 31일)
 */
export function AdminAiDemandPage() {
  useDocumentTitle("AI 범위 밖 질문 · 관리자");
  const today = useTodayKst();
  const [period, setPeriod] = useState<Period>("7");
  const from = addDays(today, -(Number(period) - 1));
  const stats = useQuery(opsUnmetDemandQuery(from, today, TOP_LIMIT));

  const renderBody = () => {
    if (stats.isPending) return <LoadingBlock label="통계를 불러오는 중이에요" />;
    if (stats.isError) {
      if (isApiError(stats.error, 403)) return <AdminForbidden />;
      return (
        <ErrorState
          error={stats.error}
          title="통계 없음"
          messages={STATS_MESSAGES}
          onRetry={() => void stats.refetch()}
        />
      );
    }
    const { total, byCategory, top } = stats.data;
    if (total === 0) {
      return (
        <EmptyState
          icon="message-square"
          title="이 기간에는 범위 밖 질문이 없어요"
          description="챗봇이 답하지 못한 질문이 생기면 분류별로 여기에 모여요."
        />
      );
    }
    return (
      <>
        <p className={styles.summary}>
          모두 <strong>{total.toLocaleString("ko-KR")}건</strong>
        </p>
        <CategoryBars items={byCategory} total={total} />
        <h3 className={styles.subTitle}>많이 나온 요청 (상위 {TOP_LIMIT})</h3>
        {top.length === 0 ? (
          <p className="micro">요약이 남은 질문이 없어요.</p>
        ) : (
          <>
            <ol className={styles.topList} aria-label="많이 나온 요청">
              {top.map((item, index) => (
                <li key={`${item.category}:${item.reason}`} className={styles.topItem}>
                  <span className={styles.topRank}>{index + 1}</span>
                  <span className={styles.topReason}>
                    <Badge tone="muted">{categoryLabel(item.category)}</Badge>
                    {item.reason}
                  </span>
                  <span className={styles.topCount}>{item.count}건</span>
                </li>
              ))}
            </ol>
            <p className="micro">
              요약은 Claude가 쓴 한 줄이고 질문 원문이 아니에요. 글자가 같은 요약끼리만 세서 합이 전체보다 작을 수 있어요.
            </p>
          </>
        )}
      </>
    );
  };

  return (
    <div className="panel">
      <div className={styles.headRow}>
        <h2 className={styles.title}>AI 범위 밖 질문</h2>
        <p className="micro">
          {formatDateKo(from)} ~ {formatDateKo(today)}
        </p>
      </div>
      <div className={styles.toolbar}>
        <SegmentedTabs tabs={PERIODS} value={period} onChange={setPeriod} aria-label="기간" />
      </div>
      {renderBody()}
    </div>
  );
}

/** 분류별 가로 막대. 한 가지 값(건수)이라 한 색, 많은 순, 막대 끝에 건수·비율을 바로 적는다 */
function CategoryBars({ items, total }: { items: UnmetDemandCategoryCount[]; total: number }) {
  const sorted = [...items].sort((a, b) => b.count - a.count);
  const max = Math.max(1, ...sorted.map((item) => item.count));
  return (
    <ul className={styles.bars} aria-label="분류별 질문 수">
      {sorted.map((item) => {
        const percent = Math.round((item.count / total) * 100);
        return (
          <li key={item.category} className={styles.barRow} title={CATEGORY_LABELS[item.category]?.hint}>
            <span className={styles.barLabel}>{categoryLabel(item.category)}</span>
            <span className={styles.barTrack} aria-hidden="true">
              <span className={styles.barFill} style={{ width: `${(item.count / max) * 100}%` }} />
            </span>
            <span className={styles.barValue}>
              {item.count}건 <span className="micro">({percent}%)</span>
            </span>
          </li>
        );
      })}
    </ul>
  );
}
