import { useEffect, useState } from "react";
import { serverNow } from "./server-clock";

/** setTimeout 상한(약 24.8일) — 넘으면 상한에서 한 번 깨어나 다시 잰다 */
const MAX_DELAY_MS = 2_147_483_647;

/**
 * 주어진 시각(서버 기준)이 되면 한 번 다시 그린다. 시간이 흐르는 것만으로는 다시 그리지 않아서,
 * 만료처럼 「그 시각이 지나면 보이는 게 바뀌는」 값을 쓸 때 부른다. null 이면 아무것도 안 한다.
 *
 * 기준은 「이번 렌더가 그 시각 전에 그려졌는가」다 — 그 시각 뒤에 그린 렌더가 나올 때까지 타이머를 다시 건다.
 * 그래서 렌더와 effect 사이에 시각이 지나도(곧바로 한 번 더 그림), 상한보다 먼 시각이어도(상한에서 깨어나 다시 잼) 놓치지 않는다
 */
export function useRerenderAt(at: string | number | null | undefined): void {
  const [, redraw] = useState(0);
  const target = at === null || at === undefined ? null : typeof at === "number" ? at : new Date(at).getTime();
  const renderedAt = serverNow();
  useEffect(() => {
    if (target === null || !Number.isFinite(target) || renderedAt >= target) return;
    const delay = Math.max(0, target - serverNow());
    const timer = window.setTimeout(() => redraw((n) => n + 1), Math.min(delay + 50, MAX_DELAY_MS));
    return () => window.clearTimeout(timer);
  }, [target, renderedAt]);
}
