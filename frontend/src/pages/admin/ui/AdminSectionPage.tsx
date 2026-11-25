import { useDocumentTitle } from "@/shared/lib";
import { EmptyState, type IconName } from "@/shared/ui";

interface Props {
  title: string;
  description: string;
  icon: IconName;
}

/**
 * 아직 채우지 않은 관리자 패널 자리 (6.3.5 골격). 서비스 상태(6.2.6)·쿠폰 이벤트(OPS-24)
 * 작업이 이 자리를 실제 패널로 바꾼다 — 라우트·메뉴는 그대로 둔다
 */
export function AdminSectionPage({ title, description, icon }: Props) {
  useDocumentTitle(`${title} · 관리자`);
  return (
    <div className="panel">
      <EmptyState as="h2" icon={icon} title={title} description={description} />
    </div>
  );
}
