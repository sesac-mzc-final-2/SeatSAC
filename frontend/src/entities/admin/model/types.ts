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
