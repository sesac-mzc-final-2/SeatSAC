import type { PaymentPrepareResult } from "@/entities/booking";
import type { TossPayWindowResult } from "./portone";

/**
 * 개발 서버 전용 가짜 토스페이 결제창 (`npm run dev:mock`). `import.meta.env.DEV`일 때만 불러와 배포본에는 들어가지 않는다.
 * 고른 결과를 목 서버(`/api/mock-portone/{paymentId}`)에 남긴다 — 실제 포트원처럼 완료 확인(`/pay`)이 서버에서 결과를 읽는다.
 */
export function openMockTossPay(prepared: PaymentPrepareResult): Promise<TossPayWindowResult> {
  return new Promise((resolve) => {
    const overlay = document.createElement("div");
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.setAttribute("aria-label", "토스페이 결제창 (개발용 가짜)");
    overlay.style.cssText =
      "position:fixed;inset:0;z-index:1000;display:grid;place-items:center;background:rgba(0,0,0,.6);padding:16px";

    const box = document.createElement("div");
    box.style.cssText =
      "width:min(360px,100%);padding:28px 24px;border-radius:16px;background:#fff;color:#191f28;font-family:inherit;text-align:center";
    const title = document.createElement("p");
    title.textContent = "toss pay · 개발용 가짜 결제창";
    title.style.cssText = "margin:0;color:#3182f6;font-weight:700;font-size:15px";
    const amount = document.createElement("p");
    amount.textContent = `${prepared.amount.toLocaleString("ko-KR")}원`;
    amount.style.cssText = "margin:14px 0 4px;font-size:26px;font-weight:700";
    const order = document.createElement("p");
    order.textContent = prepared.orderName;
    order.style.cssText = "margin:0 0 22px;color:#6b7684;font-size:13px";
    box.append(title, amount, order);

    // abandoned: 결제창만 닫고 포트원 기록은 「준비」로 남은 경우 — 서버도 결과를 모른다(503)
    const choices: { label: string; result: "paid" | "failed" | "canceled" | "abandoned"; primary?: boolean }[] = [
      { label: "결제하기", result: "paid", primary: true },
      { label: "PG 거절로 끝내기", result: "failed" },
      { label: "결제 취소 (사용자 취소)", result: "canceled" },
      { label: "창만 닫기 (결과 미확정)", result: "abandoned" },
    ];
    for (const choice of choices) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = choice.label;
      button.style.cssText = `display:block;width:100%;margin-top:8px;padding:13px;border:0;border-radius:10px;font:inherit;font-weight:600;cursor:pointer;${
        choice.primary ? "background:#3182f6;color:#fff" : "background:#f2f4f6;color:#333d4b"
      }`;
      button.addEventListener("click", () => {
        void fetch(`/api/mock-portone/${prepared.paymentId}`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ result: choice.result }),
        }).finally(() => {
          overlay.remove();
          resolve(
            choice.result === "paid"
              ? { kind: "done" }
              : { kind: "closed", message: choice.result === "failed" ? "PG 거절(가짜)" : "결제창을 닫았어요" },
          );
        });
      });
      box.append(button);
    }

    overlay.append(box);
    document.body.append(overlay);
  });
}
