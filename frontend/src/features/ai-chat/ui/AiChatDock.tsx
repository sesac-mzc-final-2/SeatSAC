import { useQuery } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { chatPresetsQuery } from "@/entities/ai-chat";
import { useSession } from "@/entities/session";
import { ApiError } from "@/shared/api";
import { Icon } from "@/shared/ui";
import { useAiChat } from "../model/use-ai-chat";
import { ChatPanel } from "./ChatPanel";
import styles from "./AiChatDock.module.css";

/**
 * 로그인 후 우하단 챗봇 (3.3.7, AI-01·AI-04). 로그인하면 먼저 `GET /ai/presets` 를 부른다:
 * - 200 → 버튼 표시, 추천 질문 칩
 * - 404(gateway 플래그 `APP_AI_ENABLED=false`, 라우트 없음)·그 밖의 4xx → 버튼 자체를 숨긴다
 * - 503(ai 파드 없음 — 평소 `ng-ai` 0대)·네트워크 오류 → 버튼은 두고 창 안에서 비활성 안내 + 다시 시도
 */
export function AiChatDock() {
  const session = useSession();
  const authenticated = session.status === "authenticated";
  const presets = useQuery({ ...chatPresetsQuery(), enabled: authenticated });
  const chat = useAiChat();
  const [open, setOpen] = useState(false);
  const launcherRef = useRef<HTMLButtonElement>(null);

  if (!authenticated) return null;
  if (presets.isPending) return null;
  const error = presets.error;
  if (error instanceof ApiError && error.status < 500) return null;

  const unavailable = presets.isError;
  const close = () => {
    setOpen(false);
    launcherRef.current?.focus();
  };

  return (
    <>
      {open && (
        <ChatPanel
          chat={chat}
          presets={presets.data?.items ?? []}
          unavailable={unavailable}
          onRetryAvailability={() => void presets.refetch()}
          retryingAvailability={presets.isFetching}
          onClose={close}
        />
      )}
      <button
        ref={launcherRef}
        type="button"
        className={styles.launcher}
        data-open={open || undefined}
        onClick={() => (open ? close() : setOpen(true))}
        aria-expanded={open}
        aria-label={open ? "AI 상영 도우미 닫기" : "AI 상영 도우미 열기"}
      >
        <Icon name={open ? "x" : "sparkles"} size={20} />
        <span className={styles.label} aria-hidden="true">
          {open ? "닫기" : "AI에게 묻기"}
        </span>
        {chat.isPending && !open && <span className={styles.busy} aria-hidden="true" />}
      </button>
      {/* 휴대폰에서 페이지 맨 아래(푸터·마지막 버튼)가 떠 있는 버튼에 가리지 않게 그만큼 자리를 둔다 */}
      <div className={styles.spacer} aria-hidden="true" />
    </>
  );
}
