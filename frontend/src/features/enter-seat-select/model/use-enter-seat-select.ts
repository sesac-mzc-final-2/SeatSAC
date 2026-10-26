import { useCallback } from "react";
import { useNavigate } from "react-router";
import { useSession } from "@/entities/session";
import { routes } from "@/shared/config";
import { useToast } from "@/shared/ui";

/**
 * W3 → W4 경계: 회차를 누르면 로그인 여부를 먼저 확인한다.
 * - 로그인됨 → `/screenings/{screeningId}` (남궁일 좌석 선택 화면)
 * - 비로그인 → Hosted UI로 보내고, 콜백 뒤 그 회차로 돌아온다 (returnTo)
 */
export function useEnterSeatSelect() {
  const session = useSession();
  const navigate = useNavigate();
  const toast = useToast();

  return useCallback(
    (screeningId: number) => {
      const target = routes.screening(screeningId);
      switch (session.status) {
        case "authenticated":
          void navigate(target);
          return;
        case "anonymous":
          session.signIn(target);
          return;
        case "unavailable":
          toast.show("로그인 설정이 없어 예매를 진행할 수 없습니다. (VITE_COGNITO_* 확인)", { tone: "error" });
          return;
        case "loading":
          return;
      }
    },
    [session, navigate, toast],
  );
}
