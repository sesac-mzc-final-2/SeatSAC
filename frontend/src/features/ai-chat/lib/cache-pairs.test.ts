import type { ChatCacheRef, ChatPath, ChatResponse } from "@/entities/ai-chat";
import { describe, expect, it } from "vitest";
import type { ChatEntry } from "../model/use-ai-chat";
import { cacheNotes, demoTotals, fasterLabel } from "./cache-pairs";

const response = (
  path: ChatPath,
  cache: ChatCacheRef | null | undefined,
  tokens: [number, number] = [0, 0],
  latencyMs = 30,
): ChatResponse => ({
  sessionId: "0b9f1c3e-6d0a-4b7e-9a51-1f2e3d4c5b6a",
  answer: "답",
  path,
  columns: null,
  rows: null,
  rowCount: 0,
  truncated: false,
  sql: null,
  presetId: null,
  asOf: "2026-11-17T19:42:00+09:00",
  llm: { calls: tokens[0] + tokens[1] > 0 ? 1 : 0, inputTokens: tokens[0], outputTokens: tokens[1], cacheReadTokens: 3810 },
  latencyMs,
  rejection: null,
  ...(cache === undefined ? {} : { cache }),
});

/** 질문·답을 번갈아 넣은 대화 (id 는 1부터) */
function talk(...turns: [string, ChatResponse][]): ChatEntry[] {
  const entries: ChatEntry[] = [];
  for (const [text, answer] of turns) {
    entries.push({ id: entries.length + 1, kind: "question", text });
    entries.push({ id: entries.length + 1, kind: "answer", response: answer });
  }
  return entries;
}

const stored = (entryId: string): ChatCacheRef => ({ entryId, stored: true, similarity: null });
const hit = (entryId: string, similarity: number | null = null): ChatCacheRef => ({ entryId, stored: false, similarity });

describe("cacheNotes (3.4.20 캐시 짝)", () => {
  it("정확 일치: 같은 entryId 를 저장한 앞선 답과 짝지어 실제 값으로 뺀다", () => {
    const entries = talk(
      ["오늘 20시 이후에 시작하는 영화 알려줘", response("NL2SQL", stored("aaaaaaaaaaaa"), [412, 58], 1860)],
      ["오늘 20시 이후에 시작하는 영화 알려줘", response("CACHE_EXACT", hit("aaaaaaaaaaaa"), [0, 0], 31)],
    );
    const notes = cacheNotes(entries);
    expect(notes.get(2)).toEqual({ kind: "stored" });
    expect(notes.get(4)).toEqual({
      kind: "paired",
      semantic: false,
      similarity: null,
      pair: {
        questionNo: 1,
        question: "오늘 20시 이후에 시작하는 영화 알…",
        inputTokens: 412,
        outputTokens: 58,
        savedTokens: 470, // 412 + 58 — 프롬프트 캐시 읽기 3,810 은 넣지 않는다
        pairLatencyMs: 1860,
        latencyMs: 31,
        fasterMs: 1829,
      },
    });
  });

  it("의미 캐시: 유사도와 함께 짝짓는다", () => {
    const entries = talk(
      ["내일 날씨", response("OUT_OF_SCOPE", null, [400, 20])],
      ["오늘 20시 이후 영화", response("PRESET", stored("bbbbbbbbbbbb"), [400, 60], 1500)],
      ["오늘 21시 넘어서 하는 영화 보여줘", response("CACHE_SEMANTIC", hit("bbbbbbbbbbbb", 0.981), [0, 0], 40)],
    );
    const note = cacheNotes(entries).get(6);
    expect(note).toMatchObject({ kind: "paired", semantic: true, similarity: 0.981 });
    expect(note?.kind === "paired" && note.pair).toMatchObject({
      questionNo: 2,
      question: "오늘 20시 이후 영화",
      savedTokens: 460,
      fasterMs: 1460,
    });
  });

  it("짝이 대화 안에 없으면(다른 사용자·미리 넣어 둔 질문) 숫자 없이 밖이라고만 한다", () => {
    const entries = talk(
      ["오늘 20시 이후 영화", response("PRESET", stored("bbbbbbbbbbbb"), [400, 60])],
      ["조조 영화 뭐 있어?", response("CACHE_EXACT", hit("cccccccccccc"))],
    );
    expect(cacheNotes(entries).get(4)).toEqual({ kind: "outside" });
  });

  it("짝은 앞선 답에서만 찾고, 다시 저장됐으면 가장 최근 것이다", () => {
    const entries = talk(
      ["가", response("CACHE_EXACT", hit("aaaaaaaaaaaa"))], // 저장한 답이 뒤에 있다 — 짝이 아니다
      ["나", response("NL2SQL", stored("aaaaaaaaaaaa"), [100, 10], 900)],
      ["다", response("NL2SQL", stored("aaaaaaaaaaaa"), [300, 30], 1200)], // 만료 뒤 다시 저장
      ["라", response("CACHE_EXACT", hit("aaaaaaaaaaaa"), [0, 0], 20)],
    );
    const notes = cacheNotes(entries);
    expect(notes.get(2)).toEqual({ kind: "outside" });
    const last = notes.get(8);
    expect(last?.kind === "paired" && last.pair).toMatchObject({ questionNo: 3, savedTokens: 330, fasterMs: 1180 });
  });

  it("cache 가 null 이거나 없는(옛 ai) 답에는 아무것도 붙이지 않는다", () => {
    const entries = talk(
      ["내 예매", response("MY_BOOKINGS", null)],
      ["오늘 20시 이후", response("CACHE_SEMANTIC", undefined)],
      ["강남 회차", response("NL2SQL", undefined, [455, 212])],
    );
    expect(cacheNotes(entries).size).toBe(0);
  });

  it("오류 항목이 섞여도 질문 번호는 질문 순서대로다", () => {
    const entries: ChatEntry[] = [
      { id: 1, kind: "question", text: "첫 질문" },
      { id: 2, kind: "error", question: "첫 질문", info: { message: "잠시 뒤", retryable: true, cooldownSeconds: null }, traceId: null },
      ...talk(
        ["둘째 질문", response("NL2SQL", stored("aaaaaaaaaaaa"), [100, 10], 500)],
        ["둘째 질문", response("CACHE_EXACT", hit("aaaaaaaaaaaa"), [0, 0], 30)],
      ).map((entry) => ({ ...entry, id: entry.id + 2 })),
    ];
    const note = cacheNotes(entries).get(6);
    expect(note?.kind === "paired" && note.pair.questionNo).toBe(2);
  });
});

describe("demoTotals (대화 누적 합계)", () => {
  const entries = talk(
    ["오늘 20시 이후 영화", response("NL2SQL", stored("aaaaaaaaaaaa"), [412, 58], 1860)],
    ["오늘 20시 이후 영화", response("CACHE_EXACT", hit("aaaaaaaaaaaa"), [0, 0], 31)],
    ["오늘 21시 넘어서 하는 영화", response("CACHE_SEMANTIC", hit("aaaaaaaaaaaa", 0.95), [0, 0], 38)],
    ["조조 영화 뭐 있어?", response("CACHE_EXACT", hit("cccccccccccc"))],
    ["내 예매", response("MY_BOOKINGS", null)],
    ["내일 날씨", response("OUT_OF_SCOPE", null, [400, 21])],
  );
  const notes = cacheNotes(entries);

  it("쓴 토큰은 입력+출력 합, 아낀 토큰은 짝을 찾은 캐시 답만 더한다", () => {
    expect(demoTotals(entries, notes)).toEqual({
      questions: 6,
      claudeAnswers: 2,
      cacheAnswers: 3,
      usedTokens: 412 + 58 + 400 + 21,
      savedTokens: 470 + 470,
      unpairedCacheAnswers: 1,
    });
  });

  it("「여기부터 다시 세기」: 그 뒤 항목만 세되 짝은 대화 전체에서 찾는다", () => {
    // 첫 질문·답(id 1·2) 뒤부터
    expect(demoTotals(entries, notes, 2)).toEqual({
      questions: 5,
      claudeAnswers: 1,
      cacheAnswers: 3,
      usedTokens: 421,
      savedTokens: 940,
      unpairedCacheAnswers: 1,
    });
    expect(demoTotals(entries, notes, 12)).toMatchObject({ questions: 0, usedTokens: 0, savedTokens: 0 });
  });
});

describe("fasterLabel", () => {
  it("빠르면 「빠름」, 더 걸렸으면 차이를 그대로 적는다", () => {
    expect(fasterLabel(1829)).toBe("1.83초 빠름");
    expect(fasterLabel(-240)).toBe("0.24초 더 걸림");
    expect(fasterLabel(0)).toBe("걸린 시간 같음");
  });
});
