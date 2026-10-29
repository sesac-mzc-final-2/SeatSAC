import { useState, type FormEvent } from "react";
import type { PayRequest } from "@/entities/booking";
import { Button } from "@/shared/ui";
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
  onSubmit: (body: PayRequest) => void;
  /** 결제 요청 중 (버튼을 잠근다 — 예매당 1회라 두 번 눌리면 안 된다) */
  pending: boolean;
  disabled?: boolean;
}

const EMPTY: CardInput = { number: "", expiry: "", holder: "" };

/** W5 모의 카드 입력. 카드 번호는 상태로만 두고 저장·로그하지 않는다 */
export function CardForm({ onSubmit, pending, disabled = false }: Props) {
  const [card, setCard] = useState<CardInput>(EMPTY);
  const [errors, setErrors] = useState<CardErrors>({});

  const change = (field: keyof CardInput, value: string) => {
    setCard((current) => ({ ...current, [field]: field === "expiry" ? formatExpiry(value) : value }));
    if (errors[field]) setErrors((current) => ({ ...current, [field]: undefined }));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validateCard(card);
    setErrors(found);
    if (hasCardError(found)) return;
    onSubmit({ method: "CARD", card: toPayCard(card) });
  };

  return (
    <form className={styles.form} onSubmit={submit} noValidate>
      <div className={styles.field}>
        <label htmlFor="card-number">카드 번호</label>
        <input
          id="card-number"
          name="cardNumber"
          inputMode="numeric"
          autoComplete="off"
          placeholder="4242 4242 4242 4242"
          value={card.number}
          onChange={(event) => change("number", event.target.value)}
          aria-invalid={Boolean(errors.number)}
          aria-describedby={errors.number ? "card-number-error" : undefined}
          disabled={disabled || pending}
        />
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
            placeholder="MM/YY"
            value={card.expiry}
            onChange={(event) => change("expiry", event.target.value)}
            aria-invalid={Boolean(errors.expiry)}
            aria-describedby={errors.expiry ? "card-expiry-error" : undefined}
            disabled={disabled || pending}
          />
          {errors.expiry && (
            <p className={styles.error} id="card-expiry-error">
              {errors.expiry}
            </p>
          )}
        </div>

        <div className={styles.field}>
          <label htmlFor="card-holder">카드 소유자</label>
          <input
            id="card-holder"
            name="cardHolder"
            autoComplete="off"
            placeholder="HONG GILDONG"
            value={card.holder}
            onChange={(event) => change("holder", event.target.value)}
            aria-invalid={Boolean(errors.holder)}
            aria-describedby={errors.holder ? "card-holder-error" : undefined}
            disabled={disabled || pending}
          />
          {errors.holder && (
            <p className={styles.error} id="card-holder-error">
              {errors.holder}
            </p>
          )}
        </div>
      </div>

      <p className={styles.mock}>
        모의 결제입니다. 카드 번호 끝 네 자리로 결과가 정해집니다 —{" "}
        {MOCK_CARDS.map((mock) => `${mock.suffix} ${mock.label}`).join(" · ")}. 그 밖의 번호는 승인됩니다.
      </p>

      <Button type="submit" variant="primary" block disabled={disabled || pending}>
        {pending ? "결제 처리 중…" : "결제하기"}
      </Button>
    </form>
  );
}
