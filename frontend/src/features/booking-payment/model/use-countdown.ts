import { useEffect, useState } from "react";
import { secondsLeft } from "@/entities/booking";

/**
 * 선점 만료까지 남은 초. 값은 <b>만료 시각에서 매번 계산</b>하고, 1초마다 다시 그리기만 한다 —
 * 남은 초를 따로 저장하면 탭이 멈췄다 돌아왔을 때 실제 시각과 어긋난다.
 *
 * 0이 됐다고 화면이 바로 「만료」로 바꾸지는 않는다. 실제 만료는 서버가 정하고(만료 스캔),
 * 결제 요청의 응답(409)으로 알게 된다. 이 값은 남은 시간을 보여 주기 위한 것이다.
 */
export function useCountdown(expiresAt: string | null | undefined): number {
  const [, redraw] = useState(0);

  useEffect(() => {
    if (!expiresAt || secondsLeft(expiresAt) <= 0) return;
    const timer = window.setInterval(() => {
      redraw((count) => count + 1);
      if (secondsLeft(expiresAt) <= 0) window.clearInterval(timer);
    }, 1000);
    return () => window.clearInterval(timer);
  }, [expiresAt]);

  return expiresAt ? secondsLeft(expiresAt) : 0;
}
