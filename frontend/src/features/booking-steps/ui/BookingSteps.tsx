import { Stepper } from "@/shared/ui";
import { BOOKING_STEPS, type BookingStep } from "../model/steps";

/** 예매 흐름 단계 표시. 좌석·결제 화면(남궁일)도 이 컴포넌트를 쓴다 */
export function BookingSteps({ current }: { current: BookingStep }) {
  return <Stepper steps={BOOKING_STEPS} current={current} label="예매 단계" />;
}
