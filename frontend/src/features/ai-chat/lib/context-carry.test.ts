import { describe, expect, it } from "vitest";
import type { ChatContext } from "@/entities/ai-chat";
import { continueFromOf, inheritedLabel, isContextExpired } from "./context-carry";

const context = (over: Partial<ChatContext> = {}): ChatContext => ({
  id: "k7f2a1",
  date: "2026-11-20",
  theater: "부산본점",
  movie: null,
  label: "내일 11/20(금) · 부산본점",
  inherited: [],
  ...over,
});
const table = { columns: [{ name: "starts_at", type: "text" as const }], rows: [["2026-11-20 14:00"]] };

describe("문맥 이어받기 (3.4.22)", () => {
  it("영화 버튼은 그 답의 context.id 를 continueFrom 으로 보낸다", () => {
    expect(continueFromOf({ context: context() })).toBe("k7f2a1");
  });

  it("조건이 없는 답·옛 ai 응답이면 continueFrom 을 보내지 않는다", () => {
    expect(continueFromOf({ context: null })).toBeUndefined();
    expect(continueFromOf({})).toBeUndefined();
    expect(continueFromOf({ context: context({ id: "" }) })).toBeUndefined();
  });

  it("이어받을 조건(날짜·극장)이 없는 답이면 continueFrom 을 보내지 않는다 — 영화만 있는 답", () => {
    const onlyMovie = context({ date: null, theater: null, movie: "부활남", label: "부활남" });
    expect(continueFromOf({ context: onlyMovie })).toBeUndefined();
    expect(continueFromOf({ context: context({ theater: null }) })).toBe("k7f2a1");
    expect(continueFromOf({ context: context({ date: null }) })).toBe("k7f2a1");
  });

  it("시각 조건(○시 이후)이 있는 답도 날짜가 있으니 continueFrom 을 보낸다 — 시각만으로는 판단하지 않는다", () => {
    const timed = context({ theater: null, time: "20:00", timeDirection: "after", label: "오늘 11/19(목) · 20시 이후" });
    expect(continueFromOf({ context: timed })).toBe("k7f2a1");
    // 날짜 없는 시각은 서버가 보내지 않는다. 와도 이어받을 조건으로 치지 않는다
    expect(continueFromOf({ context: { ...timed, date: null } })).toBeUndefined();
    expect(inheritedLabel({ context: { ...timed, inherited: ["date", "time"] } })).toBe("오늘 11/19(목) · 20시 이후");
  });

  it("이어받은 답만 조건 글자를 보여 준다", () => {
    expect(inheritedLabel({ context: context({ inherited: ["date", "theater"] }) })).toBe("내일 11/20(금) · 부산본점");
    expect(inheritedLabel({ context: context() })).toBeNull();
    expect(inheritedLabel({ context: null })).toBeNull();
    expect(inheritedLabel({})).toBeNull();
  });

  it("continueFrom 을 보냈는데 이어받지 못했으면 만료로 본다", () => {
    expect(isContextExpired({ ...table, context: context({ movie: "부활남" }) }, "k7f2a1")).toBe(true);
    expect(isContextExpired({ ...table, context: null }, "k7f2a1")).toBe(true);
    expect(isContextExpired({ ...table }, "k7f2a1")).toBe(true);
  });

  it("이어받았거나 continueFrom 을 보내지 않았으면 만료가 아니다", () => {
    expect(isContextExpired({ ...table, context: context({ inherited: ["date"] }) }, "k7f2a1")).toBe(false);
    expect(isContextExpired({ ...table, context: context() }, undefined)).toBe(false);
  });

  it("결과 표가 없는 답(범위 밖·거부)에는 만료 안내를 붙이지 않는다", () => {
    expect(isContextExpired({ columns: null, rows: null, context: null }, "k7f2a1")).toBe(false);
  });
});
