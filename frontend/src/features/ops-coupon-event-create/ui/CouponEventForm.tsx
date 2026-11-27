import { useState, type FormEvent } from "react";
import { Button, Icon } from "@/shared/ui";
import { isKstInput, kstInputToIso, nextMinuteKstInput } from "../lib/kst-input";
import { createErrorMessage, useCreateCouponEvent } from "../model/use-create-coupon-event";
import styles from "./CouponEventForm.module.css";

/** 계약 `CouponEventCreateRequest` 범위 — 서버(coupon)도 같은 검증을 한다. 여기서는 보내기 전에 바로 알려 주려고 */
const LIMITS = {
  title: 100,
  rate: [1, 99],
  quantity: [1, 10_000],
  duration: [1, 1_440],
} as const;

const inRange = (value: number, [min, max]: readonly [number, number]) =>
  Number.isInteger(value) && value >= min && value <= max;

interface Draft {
  title: string;
  discountRate: string;
  startsAt: string;
  durationMinutes: string;
  totalQuantity: string;
}

const initialDraft = (): Draft => ({
  title: "VIP 선착순 50% 할인",
  discountRate: "50",
  startsAt: nextMinuteKstInput(),
  durationMinutes: "60",
  totalQuantity: "100",
});

function validate(draft: Draft): string | null {
  const title = draft.title.trim();
  if (!title || title.length > LIMITS.title) return `제목은 1~${LIMITS.title}자로 적어 주세요.`;
  if (!inRange(Number(draft.discountRate), LIMITS.rate)) return "할인율은 1~99% 정수예요(100%면 결제 금액이 0원이 돼요).";
  if (!isKstInput(draft.startsAt)) return "시작 시각을 골라 주세요.";
  if (!inRange(Number(draft.durationMinutes), LIMITS.duration)) return "진행 시간은 1~1,440분(24시간)이에요.";
  if (!inRange(Number(draft.totalQuantity), LIMITS.quantity)) return "수량은 1~10,000개예요.";
  return null;
}

/**
 * 관리자 쿠폰 이벤트 만들기 (OPS-24). 대상 등급은 VIP 고정(계약). 시작 시각은 KST로 읽는다.
 * 시작 시각은 「지금 시작」 상태(처음 열었을 때·버튼을 눌렀을 때·만든 뒤)면 **보낼 때** 다음 정분으로 다시 계산한다 —
 * 폼을 1분 넘게 열어 둬도 STARTS_IN_PAST가 나지 않게. 시각을 직접 고르면 그 값을 그대로 보낸다
 * 만들고 나면 다시 「지금 시작」으로 돌려 두 번째 이벤트도 바로 만들 수 있게 한다(기간이 겹치면 coupon이 막는다)
 */
export function CouponEventForm() {
  const [draft, setDraft] = useState<Draft>(initialDraft);
  const [startNow, setStartNow] = useState(true);
  const [localError, setLocalError] = useState<string | null>(null);
  const resetStartNow = () => {
    setStartNow(true);
    setDraft((prev) => ({ ...prev, startsAt: nextMinuteKstInput() }));
  };
  const create = useCreateCouponEvent({ onSuccess: resetStartNow });

  const set = (key: keyof Draft) => (event: { target: { value: string } }) => {
    setDraft((prev) => ({ ...prev, [key]: event.target.value }));
    if (key === "startsAt") setStartNow(false);
    setLocalError(null);
    if (create.isError) create.reset();
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (create.isPending) return; // 버튼 disabled와 같은 뜻 — Enter 키 등으로 두 번 보내지 않게
    const startsAt = startNow ? nextMinuteKstInput() : draft.startsAt;
    if (startNow) setDraft((prev) => ({ ...prev, startsAt }));
    const problem = validate({ ...draft, startsAt });
    setLocalError(problem);
    if (problem) return;
    create.mutate({
      title: draft.title.trim(),
      discountRate: Number(draft.discountRate),
      startsAt: kstInputToIso(startsAt),
      durationMinutes: Number(draft.durationMinutes),
      totalQuantity: Number(draft.totalQuantity),
      eligibleGrade: "VIP",
    });
  };

  const error = localError ?? (create.isError ? createErrorMessage(create.error) : null);

  return (
    <form className={styles.form} onSubmit={submit} aria-label="쿠폰 이벤트 만들기" noValidate>
      <label className={styles.field} data-wide>
        <span className="micro">제목</span>
        <input value={draft.title} onChange={set("title")} maxLength={LIMITS.title} required />
      </label>
      <label className={styles.field}>
        <span className="micro">할인율(%)</span>
        <input type="number" inputMode="numeric" min={1} max={99} value={draft.discountRate} onChange={set("discountRate")} />
      </label>
      <label className={styles.field}>
        <span className="micro">수량(선착순)</span>
        <input type="number" inputMode="numeric" min={1} max={10_000} value={draft.totalQuantity} onChange={set("totalQuantity")} />
      </label>
      <label className={styles.field}>
        <span className="micro">시작 (KST)</span>
        <input type="datetime-local" value={draft.startsAt} onChange={set("startsAt")} />
      </label>
      <label className={styles.field}>
        <span className="micro">진행 시간(분)</span>
        <input type="number" inputMode="numeric" min={1} max={1_440} value={draft.durationMinutes} onChange={set("durationMinutes")} />
      </label>

      <div className={styles.actions} data-wide>
        <span className="micro">대상: VIP</span>
        <Button
          variant="ghost"
          size="sm"
          aria-pressed={startNow}
          onClick={() => {
            resetStartNow();
            setLocalError(null);
          }}
        >
          <Icon name="clock" size={14} /> 지금 시작
        </Button>
        <Button type="submit" variant="primary" disabled={create.isPending}>
          <Icon name="plus" size={15} /> {create.isPending ? "만드는 중…" : "이벤트 만들기"}
        </Button>
      </div>

      {error && (
        <p className={styles.error} role="alert" data-wide>
          {error}
        </p>
      )}
    </form>
  );
}
