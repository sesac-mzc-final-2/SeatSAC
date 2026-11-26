/** contracts/openapi/ops.openapi.yaml — OPS-20 서비스·노드 상태 (`GET /ops/services`) */

/** 신호등 `cinema:service_state` — 0 정상 · 1 주의 · 2 장애. 트래픽이 없거나 Prometheus를 못 읽으면 null */
export type ServiceState = 0 | 1 | 2 | null;

export interface HpaStatus {
  min: number;
  max: number;
  current: number;
  /** requests 대비 평균 CPU(%). 파드·지표가 없으면 null */
  cpuPercent: number | null;
}

export interface ServiceStatus {
  /** `gateway`·`catalog`·`booking`·`user`·`payment`·`coupon`·`ai`·`ops-api` */
  service: string;
  /** ROLLOUT 블루/그린(booking·gateway) · DEPLOYMENT 롤링 */
  kind: "ROLLOUT" | "DEPLOYMENT";
  desired: number;
  ready: number;
  state: ServiceState;
  hpa: HpaStatus | null;
  /** ops-api가 정지해 둔 상태 */
  stopped: boolean;
}

export interface NodeStatus {
  name: string;
  zone: string;
  cpuPercent: number | null;
  memoryPercent: number | null;
  capacityType: "ON_DEMAND" | "SPOT";
}

export interface ServiceStatusList {
  items: ServiceStatus[];
  nodes: NodeStatus[];
  /** 조회 시각(+09:00) */
  asOf: string;
}

/* ---------- OPS-22 일자별 오류 리포트 (`/ops/reports/errors/**`) ---------- */

/** `GET /ops/reports/errors/dates` — 리포트가 있는 날짜(KST `YYYY-MM-DD`), 최근 날짜가 먼저 */
export interface ReportDateList {
  items: string[];
}

/** `GET /ops/reports/errors?date` 200 — 같은 날짜 결과가 1시간 안에 있어 바로 준 내려받기 주소 */
export interface ReportReady {
  /** S3 presigned URL(5분). 로그에 남기지 않는다 */
  downloadUrl: string;
  rowCount: number;
  /** 쿼리가 끝난 시각(+09:00) */
  generatedAt: string;
}

/** `GET /ops/reports/errors?date` 202 — Athena 쿼리를 시작함 */
export interface ReportQueryStarted {
  queryId: string;
  state: "RUNNING";
}

/** `GET /ops/reports/errors/{queryId}` — FAILED여도 200(`state`로 구분) */
export interface ReportQueryStatus {
  state: "RUNNING" | "SUCCEEDED" | "FAILED";
  /** SUCCEEDED일 때만 */
  downloadUrl?: string;
  rowCount?: number;
  generatedAt?: string;
}

/* ---------- OPS-23 AI 범위 밖 질문 통계 (`GET /ops/ai/unmet-demand`) ---------- */

export interface UnmetDemandCategoryCount {
  /** `action_request`·`site_feature`·`recommendation`·`unrelated`·`unknown` — 늘 수 있어 string */
  category: string;
  count: number;
}

export interface UnmetDemandTopReason {
  category: string;
  /** Claude가 쓴 120자 이하 요약(질문 원문 아님) */
  reason: string;
  count: number;
}

export interface UnmetDemandStats {
  from: string;
  to: string;
  total: number;
  byCategory: UnmetDemandCategoryCount[];
  /** 요약이 있는 것만, (분류, 요약) 글자가 같은 것끼리 — `count` 합은 `total`보다 작을 수 있다 */
  top: UnmetDemandTopReason[];
}
