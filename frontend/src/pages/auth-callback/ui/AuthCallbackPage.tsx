import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import { Link, useNavigate } from "react-router";
import { useSession } from "@/entities/session";
import { myProfileQuery } from "@/entities/user";
import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { Button, ButtonLink, EmptyState, ErrorState, Icon, LoadingBlock } from "@/shared/ui";
import styles from "./AuthCallbackPage.module.css";

const PROFILE_MESSAGES: Record<string, string> = {
  IDENTITY_UNAVAILABLE: "회원 정보를 확인하는 서비스가 잠시 응답하지 않아요. 잠시 후 다시 시도해 주세요.",
};

/**
 * W7 `/callback` — Cognito 코드 교환은 인증 프로바이더가 처리한다. 토큰을 받으면 `GET /users/me` 로
 * 프로필을 만들고(첫 로그인) 저장해 둔 경로로 돌려보낸다. 프로필 호출이 실패하면 다시 시도하거나, 돌아갈 곳이
 * 프로필과 무관한 화면이면 그냥 계속할 수 있다(프로필은 마이페이지에서 다시 만들어진다).
 */
export function AuthCallbackPage() {
  useDocumentTitle("로그인");
  const session = useSession();
  const navigate = useNavigate();
  const returnTo = session.returnTo ?? routes.home();
  // 마이페이지는 프로필이 있어야 그려진다 → 프로필 실패 뒤 「그냥 계속하기」는 같은 오류로 이어지므로 보여 주지 않는다
  const returnNeedsProfile = returnTo === routes.myPage() || returnTo.startsWith(`${routes.myPage()}?`);

  const authenticated = session.status === "authenticated";
  const profile = useQuery({ ...myProfileQuery(), enabled: authenticated, retry: false });

  useEffect(() => {
    if (authenticated && profile.isSuccess) void navigate(returnTo, { replace: true });
    else if (session.status === "unavailable") void navigate(routes.home(), { replace: true });
  }, [authenticated, profile.isSuccess, session.status, returnTo, navigate]);

  if (session.error) {
    return (
      <EmptyState
        as="h1"
        icon="alert-circle"
        title="로그인을 완료하지 못했어요"
        description="로그인 창을 닫았거나 세션이 만료되었을 수 있습니다. 다시 시도해 주세요."
        action={
          <>
            <Button variant="primary" onClick={() => session.signIn(returnTo)}>
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

  if (authenticated && profile.isError) {
    return (
      <ErrorState
        as="h1"
        error={profile.error}
        title="회원 정보를 준비하지 못했어요"
        messages={PROFILE_MESSAGES}
        onRetry={() => profile.refetch()}
        action={
          returnNeedsProfile ? undefined : (
            <Link to={returnTo} replace className={styles.skip}>
              그냥 계속하기 <Icon name="arrow-right" size={13} />
            </Link>
          )
        }
      />
    );
  }

  return <LoadingBlock label={authenticated ? "회원 정보를 준비하는 중이에요" : "로그인 처리 중이에요"} />;
}
