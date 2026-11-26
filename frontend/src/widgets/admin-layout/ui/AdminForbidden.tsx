import { routes } from "@/shared/config";
import { ButtonLink, EmptyState } from "@/shared/ui";

/**
 * 관리자 권한 없음 안내. 토큰에 ADMIN 그룹이 없을 때(레이아웃), ops-api가 403 `FORBIDDEN`을 줄 때(패널) 같은 문구를 쓴다.
 * 그룹을 방금 바꿨다면 토큰이 다시 발급돼야 반영된다(로그아웃 → 다시 로그인)
 */
export function AdminForbidden() {
  return (
    <EmptyState
      icon="shield-check"
      title="관리자 권한이 필요해요"
      description="ADMIN 그룹 계정만 볼 수 있어요. 방금 권한을 받았다면 로그아웃한 뒤 다시 로그인해 주세요."
      action={<ButtonLink to={routes.home()}>홈으로</ButtonLink>}
    />
  );
}
