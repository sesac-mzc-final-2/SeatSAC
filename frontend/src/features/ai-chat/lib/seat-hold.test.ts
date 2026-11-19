import { describe, expect, it } from "vitest";
import type { ChatResponse } from "@/entities/ai-chat";
import { activeHold, hasHoldButtons, holdMessages, holdStateOf, isHoldConfirm, isHoldReply } from "./seat-hold";

const answer = (id: number, over: Partial<ChatResponse> = {}) => ({
  id,
  kind: "answer",
  response: { path: "NL2SQL", choices: null, seatHold: null, ...over } as ChatResponse,
});
const confirm = (id: number, confirmId: string) =>
  answer(id, {
    path: "HOLD_CONFIRM",
    choices: [
      { label: "J7 잡기", message: `좌석 잡기 #${confirmId}` },
      { label: "취소", message: `취소 #${confirmId}` },
    ],
    seatHold: {
      screeningId: 1,
      bookingId: null,
      seatLabels: ["J7"],
      amount: null,
      expiresAt: null,
      confirmExpiresAt: "2026-11-17T19:42:00+09:00",
      reason: null,
    },
  });

const UNTIL = Date.parse("2026-11-17T19:42:00+09:00");
/** 회차 고르기 — 회차 버튼의 문장은 확인 문장 모양이 아니다 */
const screenings = (id: number) =>
  answer(id, {
    path: "HOLD_GUIDE",
    choices: [
      { label: "별의 항해 11/17(화) 20:00 건대입구 1관", message: "별의 항해 11/17(화) 20:00 건대입구 1관" },
      { label: "한강의 밤 11/17(화) 21:00 건대입구 2관", message: "한강의 밤 11/17(화) 21:00 건대입구 2관" },
    ],
    seatHold: {
      screeningId: null,
      bookingId: null,
      seatLabels: ["J7"],
      amount: null,
      expiresAt: null,
      confirmExpiresAt: "2026-11-17T19:42:00+09:00",
      reason: "SCREENING_AMBIGUOUS",
    },
  });

describe("좌석 잡기 확인 (3.4.19 초안)", () => {
  it("확인 ID가 실린 문장만 확인·취소 버튼의 문장이다", () => {
    expect(isHoldReply("좌석 잡기 #c7f2a1")).toBe(true);
    expect(isHoldReply("취소 #c7f2a1")).toBe(true);
    expect(isHoldConfirm("취소 #c7f2a1")).toBe(false);
    expect(isHoldReply("좌석 잡기")).toBe(false);
    expect(isHoldReply("내일 잔여석 많은 회차")).toBe(false);
  });

  it("가장 최근 답이 확인 요청일 때만 그 버튼이 유효하다", () => {
    const a = confirm(2, "aaaaaa");
    const failed = { id: 4, kind: "error" };
    expect(activeHold([{ id: 1, kind: "question" }, a, { id: 3, kind: "question" }, failed])).toEqual({
      entryId: 2,
      messages: ["좌석 잡기 #aaaaaa", "취소 #aaaaaa"],
      expiresAt: Date.parse("2026-11-17T19:42:00+09:00"),
    });
    // 새 확인 요청이 오면 앞의 것은 지난 것
    expect(activeHold([a, confirm(5, "bbbbbb")])?.entryId).toBe(5);
    // 다른 답이 오면 유효한 확인 요청이 없다
    expect(activeHold([a, answer(6)])).toBeNull();
    expect(activeHold([])).toBeNull();
  });

  it("버튼을 막을지는 문장 모양이 아니라 응답 값(path·confirmExpiresAt)으로 정한다", () => {
    const seatHold = confirm(1, "aaaaaa").response.seatHold!;
    const odd = [{ label: "잡기", message: "hold-confirm:A" }];
    // 확인 요청: 버튼 문장이 달라도, 기한이 빠져도 좌석 잡기 버튼이다
    expect(hasHoldButtons(answer(1, { path: "HOLD_CONFIRM", choices: odd, seatHold }).response)).toBe(true);
    expect(
      hasHoldButtons(answer(1, { path: "HOLD_CONFIRM", choices: odd, seatHold: { ...seatHold, confirmExpiresAt: null } }).response),
    ).toBe(true);
    // 회차 고르기·「보내지 못했다」 뒤 다시 온 버튼: 기한이 있으면
    expect(hasHoldButtons(screenings(1).response)).toBe(true);
    expect(hasHoldButtons(answer(1, { path: "HOLD_RESULT", seatHold: { ...seatHold, reason: "HOLD_NOT_SENT" } }).response)).toBe(true);
    // 기한 없는 안내·결과·되묻기는 아니다 (문장이 확인 모양이어도)
    const looksLike = [{ label: "잡기", message: "좌석 잡기 #aaaaaa" }];
    expect(hasHoldButtons(answer(1, { path: "CLARIFY", choices: looksLike }).response)).toBe(false);
    expect(hasHoldButtons(answer(1, { path: "HOLD_GUIDE", seatHold: { ...seatHold, confirmExpiresAt: null } }).response)).toBe(false);
    expect(hasHoldButtons(answer(1).response)).toBe(false);
    // 기한을 읽을 수 없는 확인 요청은 유효한 것이 없다고 본다 — 버튼이 막힌다
    const broken = answer(2, { path: "HOLD_CONFIRM", choices: odd, seatHold: { ...seatHold, confirmExpiresAt: null } });
    expect(activeHold([broken])).toBeNull();
    expect(holdStateOf(broken, activeHold([broken]), holdMessages([broken]), 0)).toBe("stale");
  });

  it("회차 고르기 버튼도 기한이 있으면 그 문장들이 유효한 버튼이다", () => {
    const s = screenings(2);
    expect(activeHold([s])).toEqual({
      entryId: 2,
      messages: ["별의 항해 11/17(화) 20:00 건대입구 1관", "한강의 밤 11/17(화) 21:00 건대입구 2관"],
      expiresAt: UNTIL,
    });
  });

  it("항목별 상태: 가장 최근 요청의 것만 active, 기한이 지나면 expired, 지난 요청의 것은 stale, 상관없는 항목은 없음", () => {
    const a = confirm(2, "aaaaaa");
    const failedA = { id: 4, kind: "error", question: "좌석 잡기 #aaaaaa" };
    const other = { id: 5, kind: "error", question: "내일 잔여석 많은 회차" };
    const first = [{ id: 1, kind: "question" }, a, failedA, other];
    const state = (entry: (typeof first)[number], entries: typeof first, now: number) =>
      holdStateOf(entry, activeHold(entries), holdMessages(entries), now);
    expect(state(a, first, UNTIL - 1)).toBe("active");
    expect(state(failedA, first, UNTIL - 1)).toBe("active");
    expect(state(a, first, UNTIL)).toBe("expired");
    expect(state(failedA, first, UNTIL)).toBe("expired");
    // 좌석 잡기와 상관없는 항목 — 「다시 묻기」를 막지 않는다
    expect(state(other, first, UNTIL)).toBeUndefined();
    expect(state(first[0], first, UNTIL)).toBeUndefined();
    expect(state(answer(9), first, UNTIL)).toBeUndefined();

    // 새 확인 요청 B가 오면 A의 버튼과 A의 「다시 묻기」는 지난 것
    const b = confirm(6, "bbbbbb");
    const second = [...first, b];
    expect(state(a, second, UNTIL - 1)).toBe("stale");
    expect(state(failedA, second, UNTIL - 1)).toBe("stale");
    expect(state(b, second, UNTIL - 1)).toBe("active");
    // 어느 답의 버튼인지 알 수 없는 확인 문장(대화에 그 확인 요청이 없다)도 막는다
    expect(state({ id: 7, kind: "error", question: "좌석 잡기 #cccccc" }, second, UNTIL - 1)).toBe("stale");

    // 회차 버튼을 눌렀다 실패한 항목도 같은 규칙
    const s = screenings(8);
    const failedS = { id: 9, kind: "error", question: "한강의 밤 11/17(화) 21:00 건대입구 2관" };
    const third = [s, failedS];
    expect(state(failedS, third, UNTIL - 1)).toBe("active");
    expect(state(failedS, third, UNTIL + 1)).toBe("expired");
    expect(state(failedS, [...third, answer(10)], UNTIL - 1)).toBe("stale");
  });
});
