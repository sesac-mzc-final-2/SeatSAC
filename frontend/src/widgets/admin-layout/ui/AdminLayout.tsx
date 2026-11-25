import { NavLink, Outlet, useLocation } from "react-router";
import { isAdminUser, useSession } from "@/entities/session";
import { LoginGate } from "@/features/login";
import { routes } from "@/shared/config";
import { Icon, LoadingBlock, PageHeading, type IconName } from "@/shared/ui";
import { AdminForbidden } from "./AdminForbidden";
import styles from "./AdminLayout.module.css";

interface AdminMenu {
  to: string;
  label: string;
  icon: IconName;
  /** 개요만 정확히 일치할 때 활성 */
  end?: boolean;
}

const MENUS: readonly AdminMenu[] = [
  { to: routes.admin(), label: "개요", icon: "info", end: true },
  { to: routes.adminServices(), label: "서비스 상태", icon: "shield-check" },
  { to: routes.adminReports(), label: "오류 리포트", icon: "alert-circle" },
  { to: routes.adminAiDemand(), label: "AI 범위 밖 질문", icon: "message-square" },
  { to: routes.adminCoupons(), label: "쿠폰 이벤트", icon: "ticket" },
];

/**
 * 관리자 대시보드 레이아웃 `/admin/*` (6.3.5, ADR-119): 왼쪽 메뉴 + 패널(`Outlet`).
 * - 비로그인 → 로그인 안내 · 토큰 `cognito:groups`에 ADMIN 없음 → 권한 안내(패널·API 호출 없음)
 * - 그룹은 화면이 해석한 표시용 값이다. 실제 허용은 ops-api가 토큰을 검증해 정하고, 403이면 각 패널이 `AdminForbidden`을 보여 준다
 * - API는 `/ops/**`(`opsHttp`) — gateway를 거치지 않아 gateway를 정지해도 이 화면은 동작한다
 */
export function AdminLayout() {
  const session = useSession();
  const { pathname } = useLocation();

  const renderBody = () => {
    if (session.status === "loading") return <LoadingBlock label="로그인 상태를 확인하는 중이에요" />;
    if (session.status !== "authenticated") {
      return (
        <LoginGate
          returnTo={pathname}
          title="관리자 로그인이 필요해요"
          description="관리자 대시보드는 ADMIN 그룹 계정으로 로그인한 뒤 볼 수 있어요."
        />
      );
    }
    if (!isAdminUser(session.user)) return <AdminForbidden />;
    return (
      <div className={styles.body}>
        <nav className={styles.menu} aria-label="관리자 메뉴">
          {MENUS.map((menu) => (
            <NavLink key={menu.to} to={menu.to} end={menu.end} className={styles.menuLink}>
              <Icon name={menu.icon} />
              {menu.label}
            </NavLink>
          ))}
        </nav>
        <section className={styles.panel}>
          <Outlet />
        </section>
      </div>
    );
  };

  return (
    <div>
      <PageHeading
        eyebrow="SeatSAC 운영"
        title="관리자 대시보드"
        description="서비스 상태·정지/가동, 오류 리포트, AI 범위 밖 질문, 쿠폰 이벤트를 관리해요."
      />
      {renderBody()}
    </div>
  );
}
