import { useEffect, type ReactNode } from "react";
import { useLocation } from "react-router";
import { useSession } from "@/entities/session";
import { routes } from "@/shared/config";
import { Button, ButtonLink, EmptyState, Icon, LoadingBlock } from "@/shared/ui";

/**
 * 로그인한 사람만 들어오는 화면을 감싼다 (W3 → W4 경계: 좌석 화면 진입 전 로그인).
 *
 * W3의 회차 클릭은 누르는 순간 확인하지만, <b>공유 링크·북마크·새로고침으로 바로 들어오는 경우</b>는
 * 그 경로를 지나지 않는다. 그래서 화면 쪽에서도 한 번 더 막는다 — 돌아올 주소는 지금 경로로 둔다.
 */
export function RequireLogin({ children }: { children: ReactNode }) {
  const session = useSession();
  const location = useLocation();
  const here = `${location.pathname}${location.search}`;

  useEffect(() => {
    if (session.status === "anonymous") session.signIn(here);
  }, [session, here]);

  if (session.status === "authenticated") return children;

  if (session.status === "unavailable") {
    return (
      <EmptyState
        as="h1"
        icon="log-in"
        title="로그인 설정이 없어 예매를 진행할 수 없어요"
        description="이 환경에는 로그인(Cognito) 설정이 들어 있지 않습니다. 영화·상영시간 조회는 그대로 이용할 수 있어요."
        action={<ButtonLink to={routes.showtimes()}>상영시간표로</ButtonLink>}
      />
    );
  }

  if (session.error) {
    return (
      <EmptyState
        as="h1"
        icon="alert-circle"
        title="로그인을 완료하지 못했어요"
        description="다시 시도하거나 상영시간표로 돌아가 주세요."
        action={
          <>
            <Button variant="primary" onClick={() => session.signIn(here)}>
              <Icon name="log-in" /> 다시 로그인
            </Button>
            <ButtonLink to={routes.showtimes()}>상영시간표로</ButtonLink>
          </>
        }
      />
    );
  }

  return <LoadingBlock label={session.status === "loading" ? "로그인 확인 중이에요" : "로그인 화면으로 이동할게요"} />;
}
