import { useMutation, useQueryClient } from "@tanstack/react-query";
import { opsKeys, startOpsService, stopOpsService } from "@/entities/admin";
import { messageForError } from "@/shared/api";
import { useToast } from "@/shared/ui";

export type ServiceAction = "stop" | "start";

/** OPS-21 오류 코드 → 관리자 문구 (공통 코드 외) */
export const SERVICE_CONTROL_MESSAGES: Record<string, string> = {
  SERVICE_NOT_STOPPABLE: "이 서비스는 정지·가동 대상이 아니에요.",
  ROLLOUT_IN_PROGRESS: "블루/그린 배포가 진행 중이에요. 전환이 끝난 뒤 다시 시도해 주세요.",
  SERVICE_UNAVAILABLE: "관리자 서버(ops-api)가 클러스터를 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.",
  FORBIDDEN: "관리자(ADMIN)만 서비스를 정지·가동할 수 있어요.",
};

/**
 * 서비스 정지·가동 뮤테이션 (OPS-21). 성공·실패 모두 서비스 상태를 다시 받는다 —
 * 202는 「요청을 받음」이라 파드 수(`ready`)는 다음 조회에서 바뀐다(패널이 5초마다 다시 본다)
 */
export function useServiceControl(options: { onSettled?: () => void } = {}) {
  const queryClient = useQueryClient();
  const toast = useToast();

  return useMutation({
    mutationFn: ({ service, action }: { service: string; action: ServiceAction }) =>
      action === "stop" ? stopOpsService(service) : startOpsService(service),
    onSuccess: (result, { action }) => {
      toast.show(
        action === "stop"
          ? `${result.service} 서비스를 정지했어요. 파드가 내려가는 데 몇 초 걸려요.`
          : `${result.service} 서비스를 가동했어요(파드 ${result.desired}개). 준비되는 데 1~2분 걸려요.`,
      );
    },
    onError: (error) => {
      toast.show(messageForError(error, SERVICE_CONTROL_MESSAGES), { tone: "error" });
    },
    onSettled: () => {
      options.onSettled?.();
      return queryClient.invalidateQueries({ queryKey: opsKeys.services() });
    },
  });
}
