import { ApiError, NetworkError } from "@/shared/api";

/**
 * 선점 요청의 `Idempotency-Key`를 다음 시도에 <b>그대로 써야 하는지</b>.
 *
 * <p>booking은 2xx·4xx 응답을 키에 저장하고 같은 키로 오면 그 응답을 그대로 돌려준다. 5xx는 저장하지 않고
 * 키를 지운다(재시도 허용). 그래서 같은 키는 「결과를 모르는」 경우에만 다시 쓴다.
 * <ul>
 *   <li>응답을 못 받음(연결 끊김) — 서버는 이미 선점했을 수 있다. 같은 키면 첫 응답을 돌려받는다</li>
 *   <li>5xx — 서버가 키를 지웠으니 같은 키도 새 요청처럼 처리된다</li>
 *   <li>409 `IDEMPOTENCY_IN_PROGRESS` — 첫 요청이 아직 처리 중이다. 끝나면 같은 키로 그 결과를 받는다</li>
 * </ul>
 * 그 밖의 4xx(좌석 충돌 등)에 같은 키를 쓰면 좌석이 풀린 뒤에도 저장된 거절만 돌아온다 → 새 키.
 */
export function shouldReuseHoldKey(error: unknown): boolean {
  if (error instanceof NetworkError) return true;
  if (error instanceof ApiError) return error.status >= 500 || error.code === "IDEMPOTENCY_IN_PROGRESS";
  return false;
}

/** 같은 요청인지 — 서버는 본문 해시로 비교하므로 좌석 순서까지 같아야 같은 요청이다 */
export const holdRequestSignature = (input: { screeningId: number; seatIds: readonly number[] }): string =>
  JSON.stringify({ screeningId: input.screeningId, seatIds: input.seatIds });
