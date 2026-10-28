import { useEffect, useState } from "react";
import { Icon } from "../icon/Icon";
import styles from "./TraceCode.module.css";

type CopyState = "idle" | "copied" | "failed";

/**
 * 문의 코드 (2.2.5 결정 W-6): traceId 앞 8자리를 보여 주고, 누르면 32자리 전체를 복사한다.
 * 로그 검색은 32자리로 한다. 결과는 버튼 밖 live region 으로 알리고, 복사가 막히면 전체 코드를 그대로 보여 준다.
 */
export function TraceCode({ traceId }: { traceId: string }) {
  const [copyState, setCopyState] = useState<CopyState>("idle");

  useEffect(() => {
    if (copyState !== "copied") return;
    const timer = window.setTimeout(() => setCopyState("idle"), 2000);
    return () => window.clearTimeout(timer);
  }, [copyState]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(traceId);
      setCopyState("copied");
    } catch {
      // 클립보드 권한이 없거나 지원하지 않는 환경 → 전체 코드를 화면에 보여 준다 (아래 status)
      setCopyState("failed");
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
        문의 코드 <code>{traceId.slice(0, 8)}</code> · 복사
        <Icon name="copy" size={13} />
      </button>
      <span className={copyState === "failed" ? styles.failed : styles.copied} role="status" aria-live="polite">
        {copyState === "copied" && "문의 코드를 복사했습니다"}
        {copyState === "failed" && (
          <>
            복사하지 못했습니다. 문의 코드 전체: <code>{traceId}</code>
          </>
        )}
      </span>
    </span>
  );
}
