import { Link, useLocation } from "react-router";
import { useSession } from "@/entities/session";
import { routes } from "@/shared/config";
import { Button } from "@/shared/ui";
import styles from "./SessionControls.module.css";

/**
 * 헤더 오른쪽: 비로그인 [로그인] / 로그인 [이름 · 마이페이지 · 로그아웃].
 * 로그인은 Cognito Hosted UI로 이동하고 지금 경로를 돌아올 주소로 넘긴다 (W7은 3.3.6에서 마저 만든다).
 */
export function SessionControls() {
  const session = useSession();
  const location = useLocation();

  if (session.status === "loading") {
    return <span className={styles.placeholder} aria-hidden="true" />;
  }

  if (session.status === "authenticated" && session.user) {
    const label = session.user.name ?? session.user.email ?? "회원";
    return (
      <div className={styles.user}>
        <span className={styles.name} title={session.user.email ?? undefined}>
          {label}
          {session.user.role === "ADMIN" && <span className={styles.role}> · 관리자</span>}
        </span>
        <Link to={routes.myPage()} className={styles.link}>
          마이페이지
        </Link>
        <Button size="sm" onClick={() => session.signOut()}>
          로그아웃
        </Button>
      </div>
    );
  }

  const unavailable = session.status === "unavailable";
  return (
    <Button
      variant="ghost"
      onClick={() => session.signIn(`${location.pathname}${location.search}`)}
      disabled={unavailable}
      title={unavailable ? "로그인 설정(VITE_COGNITO_*)이 없어 비활성화됨" : undefined}
    >
      로그인
    </Button>
  );
}
