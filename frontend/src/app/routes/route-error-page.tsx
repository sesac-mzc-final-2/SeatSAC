import { isRouteErrorResponse, useRouteError } from "react-router";
import { routes } from "@/shared/config";
import { Button, ButtonLink, EmptyState, ErrorState, Icon } from "@/shared/ui";

/** 렌더링 중 잡히지 않은 오류 (API 오류는 각 화면의 ErrorState가 먼저 처리한다) */
export function RouteErrorPage() {
  const error = useRouteError();

  if (isRouteErrorResponse(error) && error.status === 404) {
    return (
      <EmptyState
        icon="alert-circle"
        title="이 페이지는 자리가 비어 있어요"
        description="주소를 다시 확인하거나 상영중 영화에서 시작해 주세요."
        action={
          <ButtonLink variant="primary" to={routes.home()}>
            상영중 영화 보기 <Icon name="arrow-right" />
          </ButtonLink>
        }
      />
    );
  }

  return (
    <ErrorState
      error={error}
      title="화면을 표시하지 못했어요"
      action={
        <>
          <Button variant="primary" onClick={() => window.location.reload()}>
            새로고침
          </Button>
          <ButtonLink to={routes.home()}>홈으로</ButtonLink>
        </>
      }
    />
  );
}
