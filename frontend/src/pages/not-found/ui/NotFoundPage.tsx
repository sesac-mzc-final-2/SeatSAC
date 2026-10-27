import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { ButtonLink, EmptyState, Icon } from "@/shared/ui";

export function NotFoundPage() {
  useDocumentTitle("페이지를 찾을 수 없어요");
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
