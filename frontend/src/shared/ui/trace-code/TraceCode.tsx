import { useEffect, useState } from "react";
import styles from "./TraceCode.module.css";

/**
 * 문의 코드 (2.2.5 결정 W-6): traceId 앞 8자리를 보여 주고, 누르면 32자리 전체를 복사한다.
 * 로그 검색은 32자리로 한다. 복사 결과는 버튼 밖 live region 으로 알린다 (aria-label 이 버튼 안 문구를 덮는다).
 */
export function TraceCode({ traceId }: { traceId: string }) {
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 2000);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(traceId);
      setCopied(true);
    } catch {
      // 클립보드 권한이 없으면 코드만 보여 준다 (title로 전체 값 확인 가능)
    }
  };

  return (
    <span className={styles.wrap}>
      <button
        type="button"
        className={styles.code}
        onClick={copy}
        title={traceId}
        aria-label={`문의 코드 ${traceId.slice(0, 8)}. 누르면 전체 코드를 복사합니다`}
      >
        문의 코드 <code>{traceId.slice(0, 8)}</code>
      </button>
      <span className={styles.copied} role="status" aria-live="polite">
        {copied ? "문의 코드를 복사했습니다" : ""}
      </span>
    </span>
  );
}
