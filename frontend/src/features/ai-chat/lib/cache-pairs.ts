import type { ChatResponse } from "@/entities/ai-chat";
import type { ChatEntry } from "../model/use-ai-chat";

/** 짝 질문을 보여 줄 때 앞에서 자르는 글자 수 (좁은 대화창) */
const QUESTION_PREVIEW = 20;

/** 캐시 답의 짝 — 같은 대화에서 같은 캐시 항목을 만든 답(처음 물은 답). 숫자는 두 응답 값의 뺄셈이다 */
export interface CachePair {
  /** 짝 질문이 이 대화의 몇 번째 질문인지 (1부터) */
  questionNo: number;
  /** 짝 질문 앞부분 — 화면이 이미 갖고 있는 내 질문에서 가져온다 */
  question: string;
  /** 짝 답이 쓴 입력·출력 토큰 (프롬프트 캐시 읽기는 넣지 않는다) */
  inputTokens: number;
  outputTokens: number;
  /** 아낀 토큰 = 짝 답의 입력 + 출력 (이 답은 0을 썼다) */
  savedTokens: number;
  /** 짝 답과 이 답이 걸린 시간 */
  pairLatencyMs: number;
  latencyMs: number;
  /** 짝 답 − 이 답. 음수면 이 답이 더 오래 걸렸다 */
  fasterMs: number;
}

/** 답 하나의 데모 정보에 덧붙일 캐시 설명 */
export type CacheNote =
  /** Claude 가 답했고 캐시에 저장했다 */
  | { kind: "stored" }
  /** 캐시 답 + 같은 대화 안에 짝이 있다 */
  | { kind: "paired"; semantic: boolean; similarity: number | null; pair: CachePair }
  /** 캐시 답인데 짝이 이 대화에 없다 (다른 사용자의 질문·미리 넣어 둔 질문) */
  | { kind: "outside" };

const preview = (text: string): string =>
  text.length > QUESTION_PREVIEW ? `${text.slice(0, QUESTION_PREVIEW).trimEnd()}…` : text;

const usedTokens = (response: ChatResponse): number => response.llm.inputTokens + response.llm.outputTokens;

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
    notes.set(entry.id, {
      kind: "paired",
      semantic: response.path === "CACHE_SEMANTIC",
      similarity: cache.similarity,
      pair: {
        questionNo: first.questionNo,
        question: preview(first.question),
        inputTokens: first.response.llm.inputTokens,
        outputTokens: first.response.llm.outputTokens,
        savedTokens: usedTokens(first.response),
        pairLatencyMs: first.response.latencyMs,
        latencyMs: response.latencyMs,
        fasterMs: first.response.latencyMs - response.latencyMs,
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
  /** 쓴 토큰 = 입력 + 출력 합 */
  usedTokens: number;
  /** 실측으로 아낀 토큰 = 짝을 찾은 캐시 답들의 절약 합 */
  savedTokens: number;
  /** 짝이 없어 절약을 셀 수 없는 캐시 답 */
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
    if (response.llm.calls > 0) totals.claudeAnswers += 1;
    totals.usedTokens += usedTokens(response);
    if (response.path !== "CACHE_EXACT" && response.path !== "CACHE_SEMANTIC") continue;
    totals.cacheAnswers += 1;
    const note = notes.get(entry.id);
    if (note?.kind === "paired") totals.savedTokens += note.pair.savedTokens;
    else totals.unpairedCacheAnswers += 1;
  }
  return totals;
}

/** 걸린 시간 차(ms) → 「1.83초 빠름」. 음수면 빠르다고 하지 않고 차이를 그대로 적는다 */
export function fasterLabel(fasterMs: number): string {
  if (fasterMs === 0) return "걸린 시간 같음";
  const seconds = (Math.abs(fasterMs) / 1000).toFixed(2);
  return fasterMs > 0 ? `${seconds}초 빠름` : `${seconds}초 더 걸림`;
}
