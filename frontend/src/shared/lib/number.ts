/** 금액은 정수 원 (docs/conventions.md 6장). `30000` → `30,000원` */
export function formatWon(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
