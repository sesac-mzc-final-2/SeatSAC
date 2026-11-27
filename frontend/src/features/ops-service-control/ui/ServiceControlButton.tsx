import { useState } from "react";
import { Button, ConfirmDialog, Icon } from "@/shared/ui";
import { useServiceControl, type ServiceAction } from "../model/use-service-control";

interface Props {
  service: string;
  /** 지금 정지 상태면 「가동」, 아니면 「정지」 */
  stopped: boolean;
}

/**
 * 서비스 한 줄의 「정지」·「가동」 버튼 → 확인 대화상자 → OPS-21.
 * 정지는 사용자에게 바로 보이는 장애라 늘 확인을 받고, 가동도 같은 흐름으로 둔다(실수로 두 번 누르지 않게)
 */
export function ServiceControlButton({ service, stopped }: Props) {
  const [open, setOpen] = useState(false);
  const control = useServiceControl({ onSettled: () => setOpen(false) });
  const action: ServiceAction = stopped ? "start" : "stop";

  return (
    <>
      <Button variant={stopped ? "primary" : "secondary"} size="sm" onClick={() => setOpen(true)}>
        <Icon name={stopped ? "rotate-cw" : "x"} size={14} />
        {stopped ? "가동" : "정지"}
      </Button>
      <ConfirmDialog
        open={open}
        title={stopped ? `${service}를 가동할까요?` : `${service}를 정지할까요?`}
        description={
          stopped
            ? "정지 전 파드 수로 되돌려요. 준비되는 데 1~2분 걸려요."
            : "파드를 0개로 줄여요. 그동안 이 서비스를 쓰는 화면은 오류를 보여 줘요. 시연이 끝나면 꼭 다시 가동해 주세요."
        }
        confirmLabel={stopped ? "가동하기" : "정지하기"}
        busyLabel={stopped ? "가동하는 중…" : "정지하는 중…"}
        busy={control.isPending}
        onConfirm={() => control.mutate({ service, action })}
        onClose={() => setOpen(false)}
      />
    </>
  );
}
