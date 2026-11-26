import { useQuery } from "@tanstack/react-query";
import { opsServicesQuery } from "@/entities/admin";
import { isApiError } from "@/shared/api";
import { routes } from "@/shared/config";
import { timeOfIso, useDocumentTitle } from "@/shared/lib";
import { ButtonLink, ErrorState, Icon, LoadingBlock } from "@/shared/ui";
import { AdminForbidden } from "@/widgets/admin-layout";
import styles from "./AdminPages.module.css";

/** `GET /ops/services` 오류 문구 (403 은 권한 안내로 따로 보여 준다) */
const SERVICES_MESSAGES: Record<string, string> = {
  SERVICE_UNAVAILABLE: "관리자 서버(ops-api)가 클러스터 상태를 읽지 못했어요. 잠시 후 다시 시도해 주세요.",
};

/**
 * 관리자 개요 `/admin` (6.3.5): `/ops` 연결 확인 겸 요약(서비스 수·정지·노드 수·기준 시각).
 * 자세한 상태·정지/가동은 「서비스 상태」 패널(6.2.6)
 */
export function AdminOverviewPage() {
  useDocumentTitle("관리자 대시보드");
  const services = useQuery(opsServicesQuery());

  if (services.isPending) return <LoadingBlock label="서비스 상태를 불러오는 중이에요" />;
  if (services.isError) {
    if (isApiError(services.error, 403)) return <AdminForbidden />;
    return (
      <ErrorState
        error={services.error}
        title="서비스 상태를 불러오지 못했어요"
        messages={SERVICES_MESSAGES}
        onRetry={() => void services.refetch()}
      />
    );
  }

  const { items, nodes, asOf } = services.data;
  const stopped = items.filter((item) => item.stopped).length;
  const failing = items.filter((item) => item.state === 2).length;
  const stats = [
    { label: "서비스", value: `${items.length}개` },
    { label: "정지 중", value: `${stopped}개` },
    { label: "장애 신호", value: `${failing}개` },
    { label: "노드", value: `${nodes.length}대` },
  ];

  return (
    <div className="panel">
      <div className={styles.headRow}>
        <h2 className={styles.title}>개요</h2>
        <p className="micro">{timeOfIso(asOf)} 기준</p>
      </div>
      <dl className={styles.stats}>
        {stats.map((stat) => (
          <div key={stat.label} className={styles.stat}>
            <dt className="micro">{stat.label}</dt>
            <dd className={styles.statValue}>{stat.value}</dd>
          </div>
        ))}
      </dl>
      <ButtonLink to={routes.adminServices()} variant="secondary">
        서비스 상태 자세히 <Icon name="arrow-right" />
      </ButtonLink>
    </div>
  );
}
