import { useEffect } from "react";
import { useNavigate } from "react-router";
import { useSession } from "@/entities/session";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { Button, ButtonLink, EmptyState, Icon, LoadingBlock } from "@/shared/ui";

/**
 * W7 `/callback` — Cognito 코드 교환은 인증 프로바이더가 처리한다. 여기서는 결과를 보고
 * 저장해 둔 경로로 돌려보내거나 실패 안내만 한다. (프로필 생성 `GET /users/me` 호출은 3.3.6)
 */
export function AuthCallbackPage() {
  useDocumentTitle("로그인");
  const session = useSession();
  const navigate = useNavigate();

  useEffect(() => {
    if (session.status === "authenticated") void navigate(session.returnTo ?? routes.home(), { replace: true });
    else if (session.status === "unavailable") void navigate(routes.home(), { replace: true });
  }, [session.status, session.returnTo, navigate]);

  if (session.error) {
    return (
      <EmptyState
        as="h1"
        icon="alert-circle"
        title="로그인을 완료하지 못했어요"
        description="로그인 창을 닫았거나 세션이 만료되었을 수 있습니다. 다시 시도해 주세요."
        action={
          <>
            <Button variant="primary" onClick={() => session.signIn(session.returnTo ?? routes.home())}>
              <Icon name="log-in" /> 다시 로그인
            </Button>
            <ButtonLink to={routes.home()}>홈으로</ButtonLink>
          </>
        }
      />
    );
  }

  if (session.status === "anonymous") {
    return (
      <EmptyState
        as="h1"
        icon="log-in"
        title="로그인 정보가 없어요"
        description="로그인 버튼을 눌러 다시 시작해 주세요."
        action={
          <Button variant="primary" onClick={() => session.signIn(routes.home())}>
            <Icon name="log-in" /> 로그인
          </Button>
        }
      />
    );
  }

  return <LoadingBlock label="로그인 처리 중이에요" />;
}
