import { routes } from "@/shared/config";
import { useDocumentTitle } from "@/shared/lib";
import { ButtonLink, EmptyState } from "@/shared/ui";

export function NotFoundPage() {
  useDocumentTitle("페이지를 찾을 수 없습니다");
  return (
    <EmptyState
      title="페이지를 찾을 수 없습니다"
      description="주소를 다시 확인해 주세요."
      action={<ButtonLink to={routes.home()}>홈으로</ButtonLink>}
    />
  );
}
