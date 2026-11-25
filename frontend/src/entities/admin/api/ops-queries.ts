import { queryOptions } from "@tanstack/react-query";
import { listOpsServices } from "./ops-api";

/** 로그아웃·다른 계정 로그인 때 `opsKeys.all` 로 전부 지운다 */
export const opsKeys = {
  all: ["ops"] as const,
  services: () => [...opsKeys.all, "services"] as const,
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
