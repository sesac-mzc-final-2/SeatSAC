import type { ChatResponse } from "@/entities/ai-chat";
import type { ChatEntry } from "../model/use-ai-chat";

/** 짝 질문을 보여 줄 때 앞에서 자르는 글자 수 (좁은 대화창) */
const QUESTION_PREVIEW = 20;

/**
 * 캐시 답의 짝 — 같은 대화에서 같은 캐시 항목을 만든 답(처음 물은 답). 숫자는 두 응답 값의 뺄셈이다.
 * 응답에 값이 없으면(null) 그 비교는 화면에서 생략한다 — NaN 을 보여 주지 않는다
 */
export interface CachePair {
  /** 짝 질문이 이 대화의 몇 번째 질문인지 (1부터, 대화 전체 기준) */
  questionNo: number;
  /** 짝 질문 앞부분 — 화면이 이미 갖고 있는 내 질문에서 가져온다 */
  question: string;
  /** 짝 답이 Claude 를 부른 횟수 */
  calls: number | null;
  /** 짝 답이 실제로 쓴 입력·출력 토큰 (프롬프트 캐시 읽기·쓰기는 넣지 않는다) */
  inputTokens: number | null;
  outputTokens: number | null;
  /** 이 답이 쓴 입력 + 출력 토큰 (캐시 답이라 보통 0) */
  tokens: number | null;
  /** 아낀 토큰 = 짝 답의 입력 + 출력 − 이 답의 입력 + 출력 */
  savedTokens: number | null;
  /** 짝 답과 이 답의 서버 처리 시간 */
  pairLatencyMs: number | null;
  latencyMs: number | null;
  /** 짝 답 − 이 답. 음수면 이 답이 더 오래 걸렸다 */
  fasterMs: number | null;
}

/**
 * 짝과 어떻게 같은가.
 * - `same`: 정확 일치 캐시이고 질문 글자도 같다
 * - `template`: 정확 일치 캐시인데 글자는 다르다 — 캐시 항목은 질문의 틀이라 말투·값(시각 등)만 다른 질문도 같은 항목이다
 * - `semantic`: 의미 캐시가 같은 뜻으로 판단했다
 */
export type PairMatch = "same" | "template" | "semantic";

/** 답 하나의 데모 정보에 덧붙일 캐시 설명 */
export type CacheNote =
  /** Claude 가 답했고 캐시에 저장했다 */
  | { kind: "stored" }
  /** 캐시 답 + 같은 대화 안에 짝이 있다 */
  | { kind: "paired"; match: PairMatch; similarity: number | null; pair: CachePair }
  /** 캐시 답인데 짝이 이 대화에 없다 (이전 대화·다른 사용자·미리 넣어 둔 질문) */
  | { kind: "outside" };

const preview = (text: string): string =>
  text.length > QUESTION_PREVIEW ? `${text.slice(0, QUESTION_PREVIEW).trimEnd()}…` : text;

/** 응답 값이 숫자가 아니면(빠졌거나 모양이 다르면) null */
const num = (value: unknown): number | null => (typeof value === "number" && Number.isFinite(value) ? value : null);

const minus = (a: number | null, b: number | null): number | null => (a === null || b === null ? null : a - b);

/** 입력 + 출력 토큰. 둘 중 하나라도 없으면 null */
function usedTokens(response: ChatResponse): number | null {
  const input = num(response.llm?.inputTokens);
  const output = num(response.llm?.outputTokens);
  return input === null || output === null ? null : input + output;
}

const isCacheAnswer = (response: ChatResponse): boolean =>
  response.path === "CACHE_EXACT" || response.path === "CACHE_SEMANTIC";

/** 질문 항목 id → 이 대화의 몇 번째 질문인지 (1부터). 「여기부터 다시 세기」와 무관하게 대화 전체 기준이다 */
export function questionNumbers(entries: ChatEntry[]): Map<number, number> {
  const numbers = new Map<number, number>();
  for (const entry of entries) {
    if (entry.kind === "question") numbers.set(entry.id, numbers.size + 1);
  }
  return numbers;
}

/**
 * 답마다 캐시 설명을 붙인다 (답 항목 id → 설명). `cache` 가 없는 답(옛 ai·저장하지 않은 답)은 빠진다.
 * 짝은 **앞선** 답 중 `entryId` 가 같고 `stored: true` 인 가장 최근 것 — 캐시가 만료돼 다시 저장됐으면 그 답이다.
 */
export function cacheNotes(entries: ChatEntry[]): Map<number, CacheNote> {
  const notes = new Map<number, CacheNote>();
  /** entryId → 그 항목을 저장한 가장 최근 답 */
  const stored = new Map<string, { questionNo: number; question: string; response: ChatResponse }>();
  let questionNo = 0;
  let question = "";
  for (const entry of entries) {
    if (entry.kind === "question") {
      questionNo += 1;
      question = entry.text;
      continue;
    }
    if (entry.kind !== "answer") continue;
    const { response } = entry;
    const cache = response.cache;
    if (!cache) continue;
    if (cache.stored) {
      stored.set(cache.entryId, { questionNo, question, response });
      notes.set(entry.id, { kind: "stored" });
      continue;
    }
    const first = stored.get(cache.entryId);
    if (!first) {
      notes.set(entry.id, { kind: "outside" });
      continue;
    }
    const sameText = first.question.trim() === question.trim();
    const tokens = usedTokens(response);
    const pairLatencyMs = num(first.response.latencyMs);
    const latencyMs = num(response.latencyMs);
    notes.set(entry.id, {
      kind: "paired",
      match: response.path === "CACHE_SEMANTIC" ? "semantic" : sameText ? "same" : "template",
      similarity: num(cache.similarity),
      pair: {
        questionNo: first.questionNo,
        question: preview(first.question),
        calls: num(first.response.llm?.calls),
        inputTokens: num(first.response.llm?.inputTokens),
        outputTokens: num(first.response.llm?.outputTokens),
        tokens,
        savedTokens: minus(usedTokens(first.response), tokens),
        pairLatencyMs,
        latencyMs,
        fasterMs: minus(pairLatencyMs, latencyMs),
      },
    });
  }
  return notes;
}

/** 대화 누적 합계 (데모 모드, 대화창 머리 아래 한 줄) */
export interface DemoTotals {
  questions: number;
  /** Claude 를 부른 답 */
  claudeAnswers: number;
  /** 캐시(`CACHE_EXACT`·`CACHE_SEMANTIC`)로 나온 답 */
  cacheAnswers: number;
  /** 쓴 토큰 = 입력 + 출력 합 (프롬프트 캐시 읽기·쓰기 제외) */
  usedTokens: number;
  /** 아낀 토큰 = 짝을 찾은 캐시 답마다 「짝 질문이 실제로 쓴 양 − 그 답이 쓴 양」의 합 */
  savedTokens: number;
  /** 짝이 없거나 값이 빠져 절약을 셀 수 없는 캐시 답 */
  unpairedCacheAnswers: number;
}

/**
 * `afterId` 보다 뒤의 항목만 센다(「여기부터 다시 세기」). 짝 찾기는 대화 전체에서 한다 —
 * 다시 세기 전에 물은 질문의 캐시로 답했어도 그 절약은 실제 값이다.
 */
export function demoTotals(entries: ChatEntry[], notes: Map<number, CacheNote>, afterId = 0): DemoTotals {
  const totals: DemoTotals = {
    questions: 0,
    claudeAnswers: 0,
    cacheAnswers: 0,
    usedTokens: 0,
    savedTokens: 0,
    unpairedCacheAnswers: 0,
  };
  for (const entry of entries) {
    if (entry.id <= afterId) continue;
    if (entry.kind === "question") totals.questions += 1;
    if (entry.kind !== "answer") continue;
    const { response } = entry;
    if ((num(response.llm?.calls) ?? 0) > 0) totals.claudeAnswers += 1;
    totals.usedTokens += usedTokens(response) ?? 0;
    if (!isCacheAnswer(response)) continue;
    totals.cacheAnswers += 1;
    const note = notes.get(entry.id);
    if (note?.kind === "paired" && note.pair.savedTokens !== null) totals.savedTokens += note.pair.savedTokens;
    else totals.unpairedCacheAnswers += 1;
  }
  return totals;
}

/** 걸린 시간 차(ms) → 「1.83초 빠름」. 음수면 빠르다고 하지 않고 차이를 그대로 적는다. 값이 없으면 null */
export function fasterLabel(fasterMs: number | null): string | null {
  if (fasterMs === null || !Number.isFinite(fasterMs)) return null;
  if (fasterMs === 0) return "걸린 시간 같음";
  const seconds = (Math.abs(fasterMs) / 1000).toFixed(2);
  return fasterMs > 0 ? `${seconds}초 빠름` : `${seconds}초 더 걸림`;
}

const count = (value: number) => value.toLocaleString("ko-KR");

/** 절약 줄 「토큰 667 절약 · 3.21초 빠름」. 비교할 값이 없는 쪽은 빼고, 둘 다 없으면 null */
export function savedLabel(pair: CachePair): string | null {
  const { savedTokens } = pair;
  const tokens =
    savedTokens === null
      ? null
      : savedTokens >= 0
        ? `토큰 ${count(savedTokens)} 절약`
        : `토큰 ${count(-savedTokens)} 더 씀`;
  const parts = [tokens, fasterLabel(pair.fasterMs)].filter((part) => part !== null);
  return parts.length > 0 ? parts.join(" · ") : null;
}

/**
 * 절약 줄의 근거 — 무엇에서 무엇을 뺐는지.
 * 「1번째 질문이 실제로 쓴 토큰(입력 455 + 출력 212)과 서버 처리 시간(3,240ms)에서 이 답(토큰 0 · 31ms)을 뺀 값」
 * 시간은 응답의 `latencyMs`(ai 안 처리 시간)라 네트워크 시간은 들어 있지 않다
 */
export function basisLabel(pair: CachePair): string | null {
  const from: string[] = [];
  const mine: string[] = [];
  if (pair.savedTokens !== null && pair.inputTokens !== null && pair.outputTokens !== null && pair.tokens !== null) {
    const calls = pair.calls !== null && pair.calls >= 2 ? `Claude ${count(pair.calls)}회 · ` : "";
    from.push(`실제로 쓴 토큰(${calls}입력 ${count(pair.inputTokens)} + 출력 ${count(pair.outputTokens)})`);
    mine.push(`토큰 ${count(pair.tokens)}`);
  }
  if (pair.fasterMs !== null && pair.pairLatencyMs !== null && pair.latencyMs !== null) {
    from.push(`서버 처리 시간(${count(pair.pairLatencyMs)}ms)`);
    mine.push(`${count(pair.latencyMs)}ms`);
  }
  if (from.length === 0) return null;
  return `${pair.questionNo}번째 질문이 ${from.join("과 ")}에서 이 답(${mine.join(" · ")})을 뺀 값`;
}
