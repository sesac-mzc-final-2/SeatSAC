import { queryOptions } from "@tanstack/react-query";
import { getUnmetDemandStats, listErrorReportDates, listOpsServices } from "./ops-api";

/** 로그아웃·다른 계정 로그인 때 `opsKeys.all` 로 전부 지운다 */
export const opsKeys = {
  all: ["ops"] as const,
  services: () => [...opsKeys.all, "services"] as const,
  reportDates: () => [...opsKeys.all, "reports", "dates"] as const,
  unmetDemand: (from: string, to: string, limit: number) =>
    [...opsKeys.all, "ai", "unmet-demand", from, to, limit] as const,
};

/**
 * OPS-20 서비스 상태. 403(권한 없음)은 다시 시도해도 같으니 재시도하지 않는다.
 * 5초 polling은 서비스 상태 패널이 `refetchInterval`로 켠다
 */
export const opsServicesQuery = () =>
  queryOptions({
    queryKey: opsKeys.services(),
    queryFn: ({ signal }) => listOpsServices(signal),
    retry: false,
  });

/** OPS-22 리포트 날짜. 파티션은 1시간마다 늘어나서 1분 동안은 다시 부르지 않는다 */
export const opsReportDatesQuery = () =>
  queryOptions({
    queryKey: opsKeys.reportDates(),
    queryFn: ({ signal }) => listErrorReportDates(signal),
    staleTime: 60_000,
    retry: false,
  });

/** OPS-23 AI 범위 밖 질문 통계 (기간 `from`~`to`, KST 포함) */
export const opsUnmetDemandQuery = (from: string, to: string, limit = 20) =>
  queryOptions({
    queryKey: opsKeys.unmetDemand(from, to, limit),
    queryFn: ({ signal }) => getUnmetDemandStats({ from, to, limit }, signal),
    staleTime: 60_000,
    retry: false,
  });
