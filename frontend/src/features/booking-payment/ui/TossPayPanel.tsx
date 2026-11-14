import { formatAmount } from "@/entities/booking";
import { Button, Icon } from "@/shared/ui";
import styles from "./TossPayPanel.module.css";

interface Props {
  amount: number;
  onPay: () => void;
  /** 결제 진행 중 (버튼을 잠근다 — 예매당 1회라 두 번 눌리면 안 된다) */
  pending: boolean;
  /** 「선점 취소하고 돌아가기」 */
  onCancel: () => void;
  cancelling: boolean;
}

const STEPS = [
  { icon: "credit-card", text: "토스페이 결제창이 열려요" },
  { icon: "shield-check", text: "토스 앱이나 QR로 결제를 마쳐요" },
  { icon: "check", text: "결제 결과는 SeatSAC 서버가 토스페이에서 직접 확인해요" },
] as const;

/** W5 결제 — 포트원 토스페이(간편결제). 카드 정보는 이 화면에서 받지 않는다 (ADR-112) */
export function TossPayPanel({ amount, onPay, pending, onCancel, cancelling }: Props) {
  const locked = pending || cancelling;

  return (
    <section className={`panel ${styles.panel}`} aria-labelledby="tosspay-title">
      <div className={styles.title}>
        <span className={styles.brand} aria-hidden="true">
          toss pay
        </span>
        <h2 id="tosspay-title">토스페이로 결제</h2>
        <span className={styles.badge}>테스트 결제</span>
      </div>

      <div className={styles.amount}>
        <span>결제 금액</span>
        <strong>{formatAmount(amount)}</strong>
      </div>

      <ol className={styles.steps}>
        {STEPS.map((step, index) => (
          <li key={step.text}>
            <span className={styles.stepIcon}>
              <Icon name={step.icon} size={16} />
            </span>
            <span className={styles.stepNo}>{index + 1}</span>
            {step.text}
          </li>
        ))}
      </ol>

      <div className={styles.note}>
        <Icon name="info" size={18} />
        <div>
          <strong>선점 시간 안에 결제창을 끝내 주세요.</strong>
          <p>시간이 지나면 결제가 승인돼도 좌석을 드릴 수 없어 자동으로 환불돼요. 결제는 예매마다 한 번만 할 수 있어요.</p>
        </div>
      </div>

      <Button variant="primary" block disabled={locked} onClick={onPay}>
        {pending ? (
          <>
            <Icon name="loader" size={17} className={styles.spin} />
            결제를 진행하고 있어요
          </>
        ) : (
          <>
            토스페이로 {formatAmount(amount)} 결제하기
            <Icon name="arrow-right" size={18} />
          </>
        )}
      </Button>
      <button type="button" className={styles.cancel} onClick={onCancel} disabled={locked}>
        {cancelling ? "취소 중…" : "선점 취소하고 돌아가기"}
      </button>
    </section>
  );
}
