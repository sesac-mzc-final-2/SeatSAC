import { opsHttp } from "@/shared/api";
import type {
  ReportDateList,
  ReportQueryStarted,
  ReportQueryStatus,
  ReportReady,
  ServiceStatusList,
  UnmetDemandStats,
} from "../model/types";

/**
 * 관리자 API (`/ops/**`, ops-api — gateway 아님). 401은 공통 갱신 흐름, ADMIN 그룹이 아니면 403 `FORBIDDEN`.
 * 정지·가동(OPS-21)·쿠폰 이벤트(OPS-24)는 각 패널 작업에서 여기에 더한다
 */

/** OPS-20 서비스·노드 상태. Prometheus를 못 읽으면 지표 칸만 null, Kubernetes API까지 못 읽으면 503 */
export const listOpsServices = (signal?: AbortSignal) => opsHttp<ServiceStatusList>("/ops/services", { signal });

/** OPS-22 리포트가 있는 날짜(드롭다운). Athena·S3 오류는 503 `REPORT_UNAVAILABLE` */
export const listErrorReportDates = (signal?: AbortSignal) =>
  opsHttp<ReportDateList>("/ops/reports/errors/dates", { signal });

/**
 * OPS-22 리포트 만들기. 같은 날짜 결과가 1시간 안에 있으면 200 `ReportReady`(바로 내려받기),
 * 없으면 202 `ReportQueryStarted`(쿼리 시작 — `getErrorReport`로 다시 본다). 두 모양은 `queryId` 유무로 가른다
 */
export const requestErrorReport = (date: string, signal?: AbortSignal) =>
  opsHttp<ReportReady | ReportQueryStarted>("/ops/reports/errors", { query: { date }, signal });

/** OPS-22 쿼리 상태. SUCCEEDED면 내려받기 주소(5분), 모르는 queryId는 404 */
export const getErrorReport = (queryId: string, signal?: AbortSignal) =>
  opsHttp<ReportQueryStatus>(`/ops/reports/errors/${encodeURIComponent(queryId)}`, { signal });

/** OPS-23 AI 범위 밖 질문 통계(ai 내부 API 결과 그대로). 기간은 최대 31일, ai를 못 부르면 503 */
export const getUnmetDemandStats = (
  params: { from: string; to: string; limit?: number },
  signal?: AbortSignal,
) => opsHttp<UnmetDemandStats>("/ops/ai/unmet-demand", { query: params, signal });

export const isReportReady = (value: ReportReady | ReportQueryStarted): value is ReportReady =>
  !("queryId" in value);
