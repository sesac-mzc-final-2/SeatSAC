import { describe, expect, it } from "vitest";
import type { ChatResponse } from "@/entities/ai-chat";
import { activeHold, hasHoldButtons, isHoldConfirm, isHoldReply } from "./seat-hold";

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

  it("버튼을 막는 조건은 문장 형식이 아니라 응답 값으로 본다", () => {
    // ai 가 확인 문장을 바꿔도(대문자 ID 등) 확인 요청으로 본다 → 지난 것·시간이 지난 것은 막힌다
    const changed = confirm(2, "AAAAAA");
    expect(isHoldReply("좌석 잡기 #AAAAAA")).toBe(false);
    expect(hasHoldButtons(changed.response)).toBe(true);
    expect(activeHold([changed])?.messages).toEqual(["좌석 잡기 #AAAAAA", "취소 #AAAAAA"]);
    // 회차 고르기 버튼도 같은 2분 기한에 묶인다
    const pick = answer(3, {
      path: "HOLD_GUIDE",
      choices: [{ label: "별의 항해 20:00", message: "별의 항해 20:00" }],
      seatHold: { ...confirm(0, "aaaaaa").response.seatHold!, screeningId: null, reason: "SCREENING_AMBIGUOUS" },
    });
    expect(hasHoldButtons(pick.response)).toBe(true);
    expect(activeHold([pick])?.messages).toEqual(["별의 항해 20:00"]);
    // 기한이 없는 안내·평소 되묻기 버튼은 막지 않는다
    expect(hasHoldButtons(answer(4, { path: "CLARIFY", choices: [{ label: "오늘", message: "오늘" }] }).response)).toBe(false);
  });
});
