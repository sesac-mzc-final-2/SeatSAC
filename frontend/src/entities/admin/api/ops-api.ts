import { opsHttp } from "@/shared/api";
import type { ServiceStatusList } from "../model/types";

/**
 * 관리자 API (`/ops/**`, ops-api — gateway 아님). 401은 공통 갱신 흐름, ADMIN 그룹이 아니면 403 `FORBIDDEN`.
 * 정지·가동(OPS-21)·리포트(OPS-22)·AI 통계(OPS-23)·쿠폰 이벤트(OPS-24)는 각 패널 작업에서 여기에 더한다
 */

/** OPS-20 서비스·노드 상태. Prometheus를 못 읽으면 지표 칸만 null, Kubernetes API까지 못 읽으면 503 */
export const listOpsServices = (signal?: AbortSignal) => opsHttp<ServiceStatusList>("/ops/services", { signal });
