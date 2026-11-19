import { describe, expect, it } from "vitest";
import type { ChatResponse } from "@/entities/ai-chat";
import { activeHold, isHoldConfirm, isHoldReply } from "./seat-hold";

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
});
