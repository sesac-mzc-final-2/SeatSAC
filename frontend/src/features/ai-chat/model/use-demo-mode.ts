import { useCallback, useState } from "react";

const STORAGE_KEY = "seatsac.ai.demo";

function readStored(): boolean {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

/**
 * 데모 모드: 답마다 처리 경로(path)·Claude 호출 수·토큰·지연·SQL 을 보여 준다 (11/17 AI 데모, 토큰 절감 확인용).
 * 화면 설정일 뿐이라 브라우저에 기억한다(토큰·질문은 저장하지 않는다)
 */
export function useDemoMode() {
  const [enabled, setEnabled] = useState(readStored);
  const toggle = useCallback(() => {
    setEnabled((prev) => {
      const next = !prev;
      try {
        window.localStorage.setItem(STORAGE_KEY, next ? "1" : "0");
      } catch {
        // 저장이 막혀도 이번 화면에서는 동작한다
      }
      return next;
    });
  }, []);
  return { enabled, toggle };
}
