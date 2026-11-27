import { useQuery } from "@tanstack/react-query";
import { opsServicesQuery, type ServiceState, type ServiceStatus } from "@/entities/admin";
import { ServiceControlButton } from "@/features/ops-service-control";
import { isApiError } from "@/shared/api";
import { timeOfIso, useDocumentTitle } from "@/shared/lib";
import { Badge, ErrorState, LoadingBlock } from "@/shared/ui";
import { AdminForbidden } from "@/widgets/admin-layout";
import styles from "./AdminPages.module.css";

/** 정지·가동 중에는 파드 수가 빨리 바뀌어 5초마다 다시 본다 */
const POLL_INTERVAL_MS = 5_000;

/** 정지/가동 대상이 아닌 서비스 — ops-api 자신(계약: 400 `SERVICE_NOT_STOPPABLE`) */
const NOT_STOPPABLE = new Set(["ops-api"]);

const SERVICES_MESSAGES: Record<string, string> = {
  SERVICE_UNAVAILABLE: "관리자 서버(ops-api)가 클러스터 상태를 읽지 못했어요. 잠시 후 다시 시도해 주세요.",
};

const STATE_LABEL: Record<Exclude<ServiceState, null>, { label: string; tone: string }> = {
  0: { label: "정상", tone: "ok" },
  1: { label: "주의", tone: "warn" },
  2: { label: "장애", tone: "down" },
};

function StateLight({ state }: { state: ServiceState }) {
  const meta = state === null ? { label: "트래픽 없음", tone: "none" } : STATE_LABEL[state];
  return (
    <span className={styles.light} data-tone={meta.tone}>
      <span className={styles.lightDot} aria-hidden="true" />
      {meta.label}
    </span>
  );
}

const percent = (value: number | null) => (value === null ? "—" : `${Math.round(value)}%`);

function hpaText(item: ServiceStatus): string {
  if (!item.hpa) return "고정";
  return `${item.hpa.min}~${item.hpa.max} · CPU ${percent(item.hpa.cpuPercent)}`;
}

/**
 * 관리자 「서비스 상태」 `/admin/services` (6.2.6, OPS-20·21): 서비스별 신호등·파드 수·HPA, 노드 CPU·메모리,
 * 서비스 정지/가동. 5초마다 다시 본다.
 * - 신호등은 Prometheus `cinema:service_state`(0 정상·1 주의·2 장애). 트래픽이 없거나 못 읽으면 「트래픽 없음」
 * - 정지는 replicas 0(정지 전 값은 ops-api가 기억), 가동은 그 값으로 되돌린다. Git과 다른 replicas는 의도된 예외(ADR-119)
 * - booking·gateway가 블루/그린 중이면 ops-api가 409로 막는다
 */
export function AdminServicesPage() {
  useDocumentTitle("서비스 상태 · 관리자");
  const services = useQuery({ ...opsServicesQuery(), refetchInterval: POLL_INTERVAL_MS });

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
  const stopped = items.filter((item) => item.stopped);

  return (
    <div className="panel">
      <div className={styles.headRow}>
        <h2 className={styles.title}>서비스 상태</h2>
        <p className="micro">{timeOfIso(asOf)} 기준 · 5초마다 갱신</p>
      </div>

      {stopped.length > 0 && (
        <p className={styles.notice} role="status">
          정지 중: <strong>{stopped.map((item) => item.service).join(", ")}</strong> — 시연이 끝나면 「가동」으로 되돌려 주세요.
        </p>
      )}

      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <caption className="sr-only">서비스별 상태</caption>
          <thead>
            <tr>
              <th scope="col">서비스</th>
              <th scope="col">신호등</th>
              <th scope="col">파드 (준비/목표)</th>
              <th scope="col">HPA</th>
              <th scope="col">
                <span className="sr-only">정지·가동</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.service}>
                <th scope="row">
                  <span className={styles.serviceName}>{item.service}</span>
                  <span className="micro"> {item.kind === "ROLLOUT" ? "블루/그린" : "롤링"}</span>
                  {item.stopped && (
                    <Badge tone="accent" className={styles.inlineBadge}>
                      정지됨
                    </Badge>
                  )}
                </th>
                <td>
                  <StateLight state={item.state} />
                </td>
                <td>
                  <span data-mismatch={item.ready !== item.desired || undefined} className={styles.pods}>
                    {item.ready}/{item.desired}
                  </span>
                </td>
                <td className="micro">{hpaText(item)}</td>
                <td className={styles.actionCell}>
                  {NOT_STOPPABLE.has(item.service) ? (
                    <span className="micro">대상 아님</span>
                  ) : (
                    <ServiceControlButton service={item.service} stopped={item.stopped} />
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h3 className={styles.subTitle}>노드</h3>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <caption className="sr-only">노드별 사용률</caption>
          <thead>
            <tr>
              <th scope="col">노드</th>
              <th scope="col">AZ</th>
              <th scope="col">CPU</th>
              <th scope="col">메모리</th>
              <th scope="col">구매</th>
            </tr>
          </thead>
          <tbody>
            {nodes.map((node) => (
              <tr key={node.name}>
                <th scope="row" className={styles.nodeName}>
                  {node.name}
                </th>
                <td>{node.zone}</td>
                <td>{percent(node.cpuPercent)}</td>
                <td>{percent(node.memoryPercent)}</td>
                <td className="micro">{node.capacityType === "SPOT" ? "스팟" : "온디맨드"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
