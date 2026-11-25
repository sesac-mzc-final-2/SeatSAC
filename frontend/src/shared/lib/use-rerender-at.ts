import { useEffect, useState } from "react";
import { serverNow } from "./server-clock";

/** setTimeout 상한(약 24.8일) — 넘으면 상한에서 한 번 깨어나 다시 잰다 */
const MAX_DELAY_MS = 2_147_483_647;

/**
 * 주어진 시각(서버 기준)이 되면 한 번 다시 그린다. 시간이 흐르는 것만으로는 다시 그리지 않아서,
 * 만료처럼 「그 시각이 지나면 보이는 게 바뀌는」 값을 쓸 때 부른다. 지난 시각·null 이면 아무것도 안 한다
 */
export function useRerenderAt(at: string | number | null | undefined): void {
  const [, redraw] = useState(0);
  const target = at === null || at === undefined ? null : typeof at === "number" ? at : new Date(at).getTime();
  useEffect(() => {
    if (target === null) return;
    const delay = target - serverNow();
    if (!Number.isFinite(delay) || delay < 0) return;
    const timer = window.setTimeout(() => redraw((n) => n + 1), Math.min(delay + 50, MAX_DELAY_MS));
    return () => window.clearTimeout(timer);
  }, [target]);
}
