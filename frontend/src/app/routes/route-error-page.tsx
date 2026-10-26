import { isRouteErrorResponse, useRouteError } from "react-router";
import { routes } from "@/shared/config";
import { Button, ButtonLink, EmptyState, ErrorState } from "@/shared/ui";

/** 렌더링 중 잡히지 않은 오류 (API 오류는 각 화면의 ErrorState가 먼저 처리한다) */
export function RouteErrorPage() {
  const error = useRouteError();

  if (isRouteErrorResponse(error) && error.status === 404) {
    return (
      <EmptyState
        title="페이지를 찾을 수 없습니다"
        description="주소를 다시 확인해 주세요."
        action={<ButtonLink to={routes.home()}>홈으로</ButtonLink>}
      />
    );
  }

  return (
    <ErrorState
      error={error}
      title="화면을 표시하지 못했습니다"
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
