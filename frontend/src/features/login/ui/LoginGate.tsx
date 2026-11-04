import { useSession } from "@/entities/session";
import { Button, Icon } from "@/shared/ui";
import styles from "./LoginGate.module.css";

interface Props {
  /** 로그인 뒤 돌아올 경로 (보통 지금 화면) */
  returnTo: string;
  title?: string;
  description?: string;
}

/**
 * 로그인이 필요한 화면(마이페이지·관리자)의 비로그인 안내 (W7 「로그인 필요」).
 * Cognito 설정이 없으면(로컬 기본) 버튼 대신 설정 안내만 보여 준다
 */
export function LoginGate({
  returnTo,
  title = "로그인이 필요해요",
  description = "예매 내역과 선호 설정은 로그인한 뒤 볼 수 있어요.",
}: Props) {
  const session = useSession();
  const unavailable = session.status === "unavailable";

  return (
    <section className={`panel ${styles.gate}`} aria-labelledby="login-gate-title">
      <div className={styles.icon} aria-hidden="true">
        <Icon name="user" size={26} strokeWidth={1.6} />
      </div>
      <div>
        <p className="eyebrow">나의 SeatSAC</p>
        <h2 id="login-gate-title" className={styles.title}>
          {title}
        </h2>
        <p className={styles.description}>{description}</p>
      </div>
      <div className={styles.actions}>
        <Button
          variant="primary"
          onClick={() => session.signIn(returnTo)}
          disabled={unavailable}
          title={unavailable ? "로그인 설정(VITE_COGNITO_*)이 없어 비활성화됨" : undefined}
        >
          <Icon name="log-in" />
          로그인
        </Button>
      </div>
      <p className={`micro ${styles.note}`}>
        {unavailable
          ? "로그인 설정(VITE_COGNITO_*)이 없어 이 환경에서는 로그인할 수 없어요."
          : "로그인 화면(AWS Cognito)으로 이동한 뒤 이 화면으로 돌아와요."}
      </p>
    </section>
  );
}
