import { useDocumentTitle } from "@/shared/lib";
import { EmptyState, type IconName } from "@/shared/ui";

interface Props {
  title: string;
  description: string;
  icon: IconName;
}

/**
 * 아직 채우지 않은 관리자 패널 자리 (6.3.5 골격). 지금은 모든 메뉴가 실제 패널이라 라우트에서는 쓰지 않는다
 * (서비스 상태 6.2.6 `AdminServicesPage` · 쿠폰 이벤트 6.3.9 `AdminCouponsPage`) — 새 메뉴를 먼저 열 때 자리로 쓴다
 */
export function AdminSectionPage({ title, description, icon }: Props) {
  useDocumentTitle(`${title} · 관리자`);
  return (
    <div className="panel">
      <EmptyState as="h2" icon={icon} title={title} description={description} />
    </div>
  );
}
