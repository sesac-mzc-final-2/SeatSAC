import { useEffect, useState } from "react";
import { todayKst } from "./date";

/** 다음 KST 자정까지 남은 ms (최소 1초) */
function msUntilNextKstMidnight(now: Date = new Date()): number {
  const kstNow = new Date(now.getTime() + 9 * 60 * 60 * 1000);
  const nextMidnightUtcMs = Date.UTC(kstNow.getUTCFullYear(), kstNow.getUTCMonth(), kstNow.getUTCDate() + 1) - 9 * 60 * 60 * 1000;
  return Math.max(1000, nextMidnightUtcMs - now.getTime());
}

/**
 * 오늘의 KST 영업일. 화면을 자정 넘어 열어 두면 값이 바뀌어 다시 렌더링된다
 * (렌더 때 한 번만 계산하면 어제 날짜 필터가 남는다)
 */
export function useTodayKst(): string {
  const [today, setToday] = useState(() => todayKst());

  useEffect(() => {
    let timer = 0;
    const schedule = () => {
      timer = window.setTimeout(() => {
        setToday(todayKst());
        schedule();
      }, msUntilNextKstMidnight());
    };
    schedule();
    return () => window.clearTimeout(timer);
  }, []);

  return today;
}
