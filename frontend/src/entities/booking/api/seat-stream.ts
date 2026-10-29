import type { SeatEvent, SeatEventType } from "../model/types";
import { seatStreamUrl } from "./booking-api";

const EVENT_TYPES: SeatEventType[] = ["HELD", "RELEASED", "CONFIRMED", "EXPIRED"];

/** 모르는 모양의 메시지는 버린다 — 유실을 허용하는 경로라 하나 못 읽어도 스냅샷으로 복구된다 */
export function parseSeatEvent(data: string): SeatEvent | null {
  let json: unknown;
  try {
    json = JSON.parse(data);
  } catch {
    return null;
  }
  if (!json || typeof json !== "object") return null;
  const candidate = json as Partial<SeatEvent>;
  if (typeof candidate.seq !== "number" || !Number.isFinite(candidate.seq)) return null;
  if (typeof candidate.screeningId !== "number") return null;
  if (!EVENT_TYPES.includes(candidate.type as SeatEventType)) return null;
  if (!Array.isArray(candidate.seatIds) || candidate.seatIds.some((id) => typeof id !== "number")) return null;
  return candidate as SeatEvent;
}

export interface SeatStreamHandlers {
  onEvent: (event: SeatEvent) => void;
  /** 연결될 때마다 (브라우저가 끊긴 뒤 다시 붙을 때도) */
  onOpen?: () => void;
  onError?: () => void;
}

/**
 * BKG-05 좌석 이벤트 구독. 재연결은 브라우저가 한다(서버가 `retry: 2000`을 먼저 보낸다) —
 * 다시 붙으면 {@link SeatStreamHandlers.onOpen}이 또 호출되니 그때 스냅샷을 다시 받으면 된다.
 *
 * `EventSource`는 헤더를 붙일 수 없어 인증 없는 경로를 쓴다(좌석 현황·SSE는 인증 불필요).
 */
export function subscribeSeatEvents(screeningId: number, handlers: SeatStreamHandlers): () => void {
  const source = new EventSource(seatStreamUrl(screeningId));

  const onSeat = (message: Event) => {
    const data = (message as MessageEvent<string>).data;
    const event = parseSeatEvent(data);
    if (event) handlers.onEvent(event);
  };

  source.addEventListener("seat", onSeat);
  if (handlers.onOpen) source.addEventListener("open", handlers.onOpen);
  if (handlers.onError) source.addEventListener("error", handlers.onError);

  return () => {
    source.removeEventListener("seat", onSeat);
    if (handlers.onOpen) source.removeEventListener("open", handlers.onOpen);
    if (handlers.onError) source.removeEventListener("error", handlers.onError);
    source.close();
  };
}
