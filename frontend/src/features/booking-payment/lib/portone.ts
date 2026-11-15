import type { PaymentPrepareResult } from "@/entities/booking";

/**
 * 결제창을 닫은 결과. 성공·취소 어느 쪽이든 화면은 같은 `paymentId`로 완료 확인(`/pay`)을 부른다 —
 * 결제 결과는 브라우저가 아니라 서버가 포트원에서 확인한다(브라우저 응답은 위변조될 수 있다).
 */
export type TossPayWindowResult =
  /** 결제창에서 결제를 마쳤다고 SDK가 알려 줬다 (확정은 서버가 한다) */
  | { kind: "done" }
  /** 사용자가 창을 닫았거나 PG가 실패를 돌려줬다 */
  | { kind: "closed"; message: string }
  /** 모바일은 결제창이 페이지를 떠났다가 `redirectUrl`로 돌아온다 — 이 페이지는 여기서 끝난다 */
  | { kind: "redirected" };

export type OpenTossPay = (prepared: PaymentPrepareResult, redirectUrl: string) => Promise<TossPayWindowResult>;

/** 목 서버(`npm run dev:mock`)가 돌려주는 가짜 상점 ID — 개발 서버에서만 가짜 결제창을 띄운다 */
export const MOCK_STORE_ID = "store-mock";

/**
 * 포트원 V2 결제창(토스페이 간편결제). SDK는 결제 화면에서만 필요해 처음 부를 때 불러온다.
 * 결제 금액·주문명·paymentId는 서버가 준비 응답으로 정한 값 그대로 넘긴다 — 서버가 완료 확인 때 금액을 대조한다.
 */
export const openTossPay: OpenTossPay = async (prepared, redirectUrl) => {
  if (import.meta.env.DEV && prepared.checkout.storeId === MOCK_STORE_ID) {
    const { openMockTossPay } = await import("./portone-mock");
    return openMockTossPay(prepared);
  }

  const PortOne = await import("@portone/browser-sdk/v2");
  const response = await PortOne.requestPayment({
    storeId: prepared.checkout.storeId,
    channelKey: prepared.checkout.channelKey,
    paymentId: prepared.paymentId,
    orderName: prepared.orderName,
    totalAmount: prepared.amount,
    currency: prepared.checkout.currency,
    payMethod: prepared.checkout.payMethod,
    easyPay: { easyPayProvider: "TOSSPAY" },
    redirectUrl,
  });

  // 리다이렉트 방식(모바일)은 응답 없이 페이지를 떠난다
  if (!response) return { kind: "redirected" };
  if (response.code !== undefined) {
    return { kind: "closed", message: response.message ?? "결제가 완료되지 않았어요." };
  }
  return { kind: "done" };
};

/**
 * 모바일 결제창이 돌아온 주소(`redirectUrl?paymentId=…&code=…`)를 읽는다.
 * `paymentId`가 없으면 결제창에서 돌아온 게 아니다.
 */
export function readTossPayReturn(search: string): { paymentId: string; closedMessage: string | null } | null {
  const params = new URLSearchParams(search);
  const paymentId = params.get("paymentId");
  if (!paymentId) return null;
  const code = params.get("code");
  return { paymentId, closedMessage: code ? (params.get("message") ?? "결제가 완료되지 않았어요.") : null };
}
