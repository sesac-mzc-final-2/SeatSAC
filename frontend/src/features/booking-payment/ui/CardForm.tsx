import { useState, type FormEvent } from "react";
import { formatAmount, type PayRequest } from "@/entities/booking";
import { Button, Icon } from "@/shared/ui";
import {
  MOCK_CARDS,
  formatExpiry,
  hasCardError,
  toPayCard,
  validateCard,
  type CardErrors,
  type CardInput,
} from "../lib/card";
import styles from "./CardForm.module.css";

interface Props {
  amount: number;
  onSubmit: (body: PayRequest) => void;
  /** 결제 요청 중 (버튼을 잠근다 — 예매당 1회라 두 번 눌리면 안 된다) */
  pending: boolean;
  /** 「선점 취소하고 돌아가기」 */
  onCancel: () => void;
  cancelling: boolean;
  disabled?: boolean;
}

const EMPTY: CardInput = { number: "", expiry: "", holder: "" };

/** 시연용 테스트 카드 (끝 네 자리가 결과를 정한다, ADR-13). 4242는 승인 */
const TEST_CARDS = [{ suffix: "4242", label: "성공" }, ...MOCK_CARDS] as const;

/** W5 모의 카드 결제 (시안). 카드 번호는 상태로만 두고 저장·로그하지 않는다 */
export function CardForm({ amount, onSubmit, pending, onCancel, cancelling, disabled = false }: Props) {
  const [card, setCard] = useState<CardInput>(EMPTY);
  const [errors, setErrors] = useState<CardErrors>({});
  const locked = disabled || pending || cancelling;

  const change = (field: keyof CardInput, value: string) => {
    setCard((current) => ({ ...current, [field]: field === "expiry" ? formatExpiry(value) : value }));
    if (errors[field]) setErrors((current) => ({ ...current, [field]: undefined }));
  };

  const fill = (suffix: string) => {
    setCard({ number: `4242 4242 4242 ${suffix}`, expiry: "12/29", holder: "SEATSAC DEMO" });
    setErrors({});
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validateCard(card);
    setErrors(found);
    if (hasCardError(found)) return;
    onSubmit({ method: "CARD", card: toPayCard(card) });
  };

  return (
    <form className={`panel ${styles.form}`} onSubmit={submit} noValidate>
      <div className={styles.title}>
        <Icon name="credit-card" size={20} />
        <h2>모의 카드 결제</h2>
        <span>안전한 데모 환경</span>
      </div>

      <div className={styles.note}>
        <Icon name="info" size={18} />
        <div>
          <strong>모의 결제입니다. 실제 결제되지 않아요.</strong>
          <p>실제 카드 정보는 입력하지 마세요. 끝 네 자리로 결과가 정해져요.</p>
          <ul>
            {MOCK_CARDS.map((mock) => (
              <li key={mock.suffix}>
                <b>{mock.suffix}</b> {mock.label}
              </li>
            ))}
          </ul>
        </div>
      </div>

      <div className={styles.fill}>
        <span>테스트 카드 채우기</span>
        {TEST_CARDS.map((test) => (
          <button type="button" key={test.suffix} disabled={locked} onClick={() => fill(test.suffix)}>
            {test.label}
          </button>
        ))}
      </div>

      <div className={styles.field}>
        <label htmlFor="card-number">
          모의 카드 번호<span>12~19자리</span>
        </label>
        <div className={styles.numberBox}>
          <input
            id="card-number"
            name="cardNumber"
            inputMode="numeric"
            autoComplete="off"
            placeholder="0000 0000 0000 0000"
            value={card.number}
            onChange={(event) => change("number", event.target.value)}
            aria-invalid={Boolean(errors.number)}
            aria-describedby={errors.number ? "card-number-error" : undefined}
            disabled={locked}
          />
          <Icon name="credit-card" size={18} />
        </div>
        {errors.number && (
          <p className={styles.error} id="card-number-error">
            {errors.number}
          </p>
        )}
      </div>

      <div className={styles.row}>
        <div className={styles.field}>
          <label htmlFor="card-expiry">유효기간</label>
          <input
            id="card-expiry"
            name="cardExpiry"
            inputMode="numeric"
            autoComplete="off"
            placeholder="월 / 년"
            value={card.expiry}
            onChange={(event) => change("expiry", event.target.value)}
            aria-invalid={Boolean(errors.expiry)}
            aria-describedby={errors.expiry ? "card-expiry-error" : undefined}
            disabled={locked}
          />
          {errors.expiry && (
            <p className={styles.error} id="card-expiry-error">
              {errors.expiry}
            </p>
          )}
        </div>

        <div className={styles.field}>
          <label htmlFor="card-holder">
            카드 소유자<span>영문 이름</span>
          </label>
          <input
            id="card-holder"
            name="cardHolder"
            autoComplete="off"
            placeholder="HONG GILDONG"
            value={card.holder}
            onChange={(event) => change("holder", event.target.value)}
            aria-invalid={Boolean(errors.holder)}
            aria-describedby={errors.holder ? "card-holder-error" : undefined}
            disabled={locked}
          />
          {errors.holder && (
            <p className={styles.error} id="card-holder-error">
              {errors.holder}
            </p>
          )}
        </div>
      </div>

      <p className={styles.consent}>
        <Icon name="shield-check" size={16} />
        카드 정보는 저장되지 않으며, 결제 시뮬레이션에만 사용돼요.
      </p>

      <Button type="submit" variant="primary" block disabled={locked}>
        {pending ? (
          <>
            <Icon name="loader" size={17} className={styles.spin} />
            결제를 처리하고 있어요
          </>
        ) : (
          <>
            {formatAmount(amount)} 결제하기
            <Icon name="arrow-right" size={18} />
          </>
        )}
      </Button>
      <button type="button" className={styles.cancel} onClick={onCancel} disabled={pending || cancelling}>
        {cancelling ? "취소 중…" : "선점 취소하고 돌아가기"}
      </button>
    </form>
  );
}
