import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ChatCacheRef, ChatResponse } from "@/entities/ai-chat";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { AiChatDock } from "./AiChatDock";
import nl2sql from "../../../../../contracts/openapi/examples/ai/chat-nl2sql.json";
import cacheSemantic from "../../../../../contracts/openapi/examples/ai/chat-cache-semantic.json";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const presets = {
  items: [{ presetId: "today_after_time", title: "오늘 ○시 이후 회차", example: "오늘 20시 이후에 시작하는 영화 알려줘" }],
};
const ENTRY = "9f2c1a7b3e4d";

// 계약 예시에 `cache` 가 있든 없든 통과하게, 이 테스트가 쓰는 값은 여기서 넣는다
/** 처음 답: Claude 1회 · 입력 455 + 출력 212 · 3,240ms (계약 예시 값) + 캐시에 저장 */
const first = { ...nl2sql.value, cache: { entryId: ENTRY, stored: true, similarity: null } } as ChatResponse;
const cached = (path: "CACHE_EXACT" | "CACHE_SEMANTIC", cache: ChatCacheRef | null, latencyMs = 38) =>
  ({ ...cacheSemantic.value, path, latencyMs, cache }) as ChatResponse;
/** `cache` 필드 자체가 없는 옛 ai 응답 */
const withoutCache = (value: object): ChatResponse => {
  const copy: Record<string, unknown> = { ...value };
  delete copy.cache;
  return copy as unknown as ChatResponse;
};

const authenticated: Session = {
  ...unavailableSession,
  status: "authenticated",
  user: { id: "00000000-0000-4000-8000-000000000001", name: "한채연", email: null },
};

/** 챗 응답을 순서대로 돌려준다 */
function mockAnswers(...answers: ChatResponse[]) {
  let next = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
    String(input) === "/api/ai/presets" ? json(200, presets) : json(200, answers[next++] ?? answers[answers.length - 1]),
  );
}

async function openDock({ demo }: { demo: boolean }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={authenticated}>
        <MemoryRouter>
          <AiChatDock />
        </MemoryRouter>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
  await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
  if (demo) await userEvent.click(screen.getByRole("button", { name: "데모 정보" }));
}

/** 질문을 보내고 그 답의 데모 정보가 그려질 때까지 기다린다 */
async function ask(question: string, nth: number) {
  await userEvent.type(screen.getByRole("textbox", { name: "질문" }), `${question}{Enter}`);
  await screen.findAllByText(question);
  const metas = await vi.waitFor(() => {
    const found = screen.getAllByLabelText("처리 정보");
    expect(found).toHaveLength(nth);
    return found;
  });
  return metas[nth - 1];
}

const totals = () => screen.getByRole("group", { name: "이 대화의 토큰 합계" });
const totalItems = () =>
  within(totals())
    .getAllByRole("listitem")
    .map((item) => item.textContent);
const SCOPE = "프롬프트 캐시 읽기·쓰기 토큰은 포함하지 않습니다";

describe("데모 모드 캐시 짝 (3.4.20)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it("처음 답에는 저장했다는 줄, 같은 질문의 캐시 답에는 짝과 실측 뺄셈을 보여 준다", async () => {
    mockAnswers(first, cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null }, 31));
    await openDock({ demo: true });
    const one = await ask("잔여석 비율 10% 이하인 오늘 회차 알려줘", 1);
    expect(
      within(one).getByText("이 답의 SQL 틀을 캐시에 저장함 — 1시간 동안 같은 틀의 질문은 Claude 없이 답할 수 있습니다"),
    ).toBeInTheDocument();
    expect(within(one).queryByText(/절약/)).not.toBeInTheDocument();

    const two = await ask("잔여석 비율 10% 이하인 오늘 회차 알려줘", 2);
    expect(within(two).getByText("정확 일치 캐시")).toBeInTheDocument();
    expect(two).toHaveTextContent("1번째 질문과 같은 질문");
    expect(two).toHaveTextContent("「잔여석 비율 10% 이하인 오늘 회차…」"); // 내 질문 앞 20자
    // 455 + 212 = 667 토큰 · 3,240ms − 31ms = 3.21초 (프롬프트 캐시 읽기 3,810 은 절약에 넣지 않는다)
    expect(within(two).getByText("토큰 667 절약 · 3.21초 빠름")).toBeInTheDocument();
    expect(
      within(two).getByText(
        "1번째 질문이 실제로 쓴 토큰(입력 455 + 출력 212)과 서버 처리 시간(3,240ms)에서 이 답(토큰 0 · 31ms)을 뺀 값",
      ),
    ).toBeInTheDocument();
    expect(two).not.toHaveTextContent("실측");
    expect(within(two).queryByText(/이 대화 밖에서/)).not.toBeInTheDocument();
  });

  it("정확 일치 캐시라도 글자가 다르면 같은 틀의 질문이라고 한다", async () => {
    mockAnswers(first, cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null }, 31));
    await openDock({ demo: true });
    await ask("오늘 20시 넘어서 시작하는 영화 제목", 1);
    const two = await ask("오늘 21시 넘어서 시작하는 영화 제목", 2);
    expect(two).toHaveTextContent("1번째 질문과 같은 틀의 질문(말투·값만 다름)");
    expect(two).not.toHaveTextContent("같은 질문 「");
    expect(within(two).getByText("토큰 667 절약 · 3.21초 빠름")).toBeInTheDocument();
  });

  it("데모 모드에서는 질문 말풍선에 대화 전체 기준 번호가 붙는다", async () => {
    mockAnswers(first, cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null }));
    await openDock({ demo: true });
    await ask("매진 임박 회차", 1);
    await ask("매진 임박 회차", 2);
    const bubbles = () => [...document.querySelectorAll("[data-question]")].map((el) => el.textContent);
    expect(bubbles()).toEqual(["1내 질문 1번: 매진 임박 회차", "2내 질문 2번: 매진 임박 회차"]);
    // 다시 세기는 합계만 — 번호는 그대로
    await userEvent.click(screen.getByRole("button", { name: "여기부터 다시 세기" }));
    expect(bubbles()).toEqual(["1내 질문 1번: 매진 임박 회차", "2내 질문 2번: 매진 임박 회차"]);
    // 데모 모드를 끄면 번호가 사라진다
    await userEvent.click(screen.getByRole("button", { name: "데모 정보" }));
    expect(bubbles()).toEqual(["내 질문: 매진 임박 회차", "내 질문: 매진 임박 회차"]);
  });

  it("답을 기다리는 동안에는 「여기부터 다시 세기」를 누를 수 없다", async () => {
    let release: (response: Response) => void = () => {};
    let chats = 0;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      chats += 1;
      if (chats === 1) return json(200, first);
      return new Promise<Response>((resolve) => {
        release = resolve;
      });
    });
    await openDock({ demo: true });
    await ask("매진 임박 회차", 1);
    const reset = screen.getByRole("button", { name: "여기부터 다시 세기" });
    expect(reset).toBeEnabled();
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "매진 임박 회차{Enter}");
    await screen.findByRole("status");
    expect(reset).toBeDisabled();
    release(json(200, cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null })));
    await vi.waitFor(() => expect(reset).toBeEnabled());
  });

  it("latencyMs 가 빠진 응답이면 시간 비교를 생략하고 NaN 을 보여 주지 않는다", async () => {
    const noLatency: Record<string, unknown> = { ...first };
    delete noLatency.latencyMs;
    mockAnswers(
      noLatency as unknown as ChatResponse,
      cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null }, 31),
    );
    await openDock({ demo: true });
    await ask("매진 임박 회차", 1);
    const two = await ask("매진 임박 회차", 2);
    expect(within(two).getByText("토큰 667 절약")).toBeInTheDocument();
    expect(within(two).getByText("1번째 질문이 실제로 쓴 토큰(입력 455 + 출력 212)에서 이 답(토큰 0)을 뺀 값")).toBeInTheDocument();
    expect(two).not.toHaveTextContent("빠름");
    expect(document.body).not.toHaveTextContent("NaN");
  });

  it("llm 이 빠진 응답이면 토큰 비교를 생략하고 답은 그대로 그린다", async () => {
    const noLlm: Record<string, unknown> = { ...first };
    delete noLlm.llm;
    mockAnswers(
      noLlm as unknown as ChatResponse,
      cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null }, 31),
    );
    await openDock({ demo: true });
    await ask("매진 임박 회차", 1);
    const two = await ask("매진 임박 회차", 2);
    expect(within(two).getByText("3.21초 빠름")).toBeInTheDocument();
    expect(within(two).getByText("1번째 질문이 서버 처리 시간(3,240ms)에서 이 답(31ms)을 뺀 값")).toBeInTheDocument();
    expect(two).not.toHaveTextContent("절약");
    expect(document.body).not.toHaveTextContent("NaN");
    // 절약을 셀 수 없는 캐시 답은 합계에서 뺀다
    expect(totalItems().slice(3)).toEqual([
      `쓴 토큰 0(입력+출력) — ${SCOPE}`,
      "아낀 토큰 0(짝 질문이 실제로 쓴 양 기준)",
      "짝 없는 캐시 답 1건은 제외",
    ]);
  });

  it("의미 캐시 답은 같은 뜻으로 판단했다는 것과 유사도를 보여 준다", async () => {
    mockAnswers(first, cached("CACHE_SEMANTIC", { entryId: ENTRY, stored: false, similarity: 0.981 }));
    await openDock({ demo: true });
    await ask("매진 임박 회차", 1);
    const two = await ask("곧 매진될 회차 보여줘", 2);
    expect(two).toHaveTextContent("1번째 질문과 같은 뜻으로 판단 · 유사도 0.98");
    expect(two).toHaveTextContent("「매진 임박 회차」");
    expect(within(two).getByText("토큰 667 절약 · 3.20초 빠름")).toBeInTheDocument(); // 3,240 − 38
  });

  it("캐시 답이 처음 답보다 오래 걸렸으면 빠르다고 하지 않는다", async () => {
    mockAnswers(first, cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null }, 3500));
    await openDock({ demo: true });
    await ask("매진 임박 회차", 1);
    const two = await ask("매진 임박 회차", 2);
    expect(within(two).getByText("토큰 667 절약 · 0.26초 더 걸림")).toBeInTheDocument();
  });

  it("짝이 이 대화에 없으면 절약 숫자 없이 대화 밖 캐시라고 알린다", async () => {
    mockAnswers(cached("CACHE_EXACT", { entryId: "c03d58e1f6a9", stored: false, similarity: null }));
    await openDock({ demo: true });
    const one = await ask("조조 영화 뭐 있어?", 1);
    expect(
      within(one).getByText(
        "이 대화 밖에서 만들어진 캐시(이전 대화 · 다른 사용자 · 미리 넣어 둔 질문) · Claude 호출 없음 · 토큰 0",
      ),
    ).toBeInTheDocument();
    expect(within(one).queryByText(/절약/)).not.toBeInTheDocument();
    expect(totalItems()).toEqual([
      "질문 1",
      "Claude 답 0",
      "캐시 답 1",
      `쓴 토큰 0(입력+출력) — ${SCOPE}`,
      "아낀 토큰 0(짝 질문이 실제로 쓴 양 기준)",
      "짝 없는 캐시 답 1건은 제외",
    ]);
  });

  it("누적 합계를 보여 주고 「여기부터 다시 세기」는 대화는 두고 합계만 비운다", async () => {
    mockAnswers(
      first,
      cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null }),
      cached("CACHE_SEMANTIC", { entryId: ENTRY, stored: false, similarity: 0.95 }),
    );
    await openDock({ demo: true });
    await ask("매진 임박 회차", 1);
    await ask("매진 임박 회차", 2);
    // 항목마다 따로 읽힌다(목록) — 프롬프트 캐시 읽기·쓰기는 넣지 않는다는 것은 title·스크린리더 글자로
    expect(totalItems()).toEqual([
      "질문 2",
      "Claude 답 1",
      "캐시 답 1",
      `쓴 토큰 667(입력+출력) — ${SCOPE}`,
      "아낀 토큰 667(짝 질문이 실제로 쓴 양 기준)",
    ]);
    expect(within(totals()).getByText(/^아낀 토큰/)).toHaveAttribute("title", SCOPE);

    await userEvent.click(within(totals()).getByRole("button", { name: "여기부터 다시 세기" }));
    expect(totalItems().slice(0, 3)).toEqual(["질문 0", "Claude 답 0", "캐시 답 0"]);
    expect(totalItems()[4]).toBe("아낀 토큰 0(짝 질문이 실제로 쓴 양 기준)");
    expect(screen.getAllByLabelText("처리 정보")).toHaveLength(2); // 대화는 그대로

    // 다시 센 뒤의 캐시 답 — 짝은 그 전에 물은 1번째 질문이다
    const three = await ask("곧 매진될 회차", 3);
    expect(three).toHaveTextContent("1번째 질문과 같은 뜻으로 판단 · 유사도 0.95");
    expect(totalItems()).toEqual([
      "질문 1",
      "Claude 답 0",
      "캐시 답 1",
      `쓴 토큰 0(입력+출력) — ${SCOPE}`,
      "아낀 토큰 667(짝 질문이 실제로 쓴 양 기준)",
    ]);
  });

  it("데모 모드가 꺼져 있으면 아무것도 그리지 않는다", async () => {
    mockAnswers(first, cached("CACHE_EXACT", { entryId: ENTRY, stored: false, similarity: null }));
    await openDock({ demo: false });
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "매진 임박 회차{Enter}");
    await screen.findByText(nl2sql.value.answer);
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "매진 임박 회차 다시{Enter}");
    await screen.findByText(cacheSemantic.value.answer);
    expect(screen.queryByLabelText("처리 정보")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "이 대화의 토큰 합계" })).not.toBeInTheDocument();
    expect(screen.queryByText(/캐시에 저장함|절약|같은 질문|다시 세기/)).not.toBeInTheDocument();
    expect(document.querySelector("[data-question]")).toHaveTextContent(/^내 질문: 매진 임박 회차$/);
  });

  it("cache 필드가 없는 옛 ai 응답이면 데모 정보는 지금 그대로다", async () => {
    mockAnswers(withoutCache(nl2sql.value), withoutCache({ ...cacheSemantic.value, path: "CACHE_SEMANTIC" }));
    await openDock({ demo: true });
    const one = await ask("매진 임박 회차", 1);
    expect(within(one).getByText("NL2SQL")).toBeInTheDocument();
    expect(within(one).getByText("Claude 1회 · 입력 455 · 출력 212")).toBeInTheDocument();
    const two = await ask("곧 매진될 회차", 2);
    expect(within(two).getByText("Claude 호출 없음 · 토큰 0")).toBeInTheDocument();
    expect(screen.queryByText(/캐시에 저장함|절약|같은 뜻|이 대화 밖/)).not.toBeInTheDocument();
    // 짝을 알 수 없는 캐시 답은 아낀 토큰에 넣지 않는다
    expect(totalItems().slice(4)).toEqual(["아낀 토큰 0(짝 질문이 실제로 쓴 양 기준)", "짝 없는 캐시 답 1건은 제외"]);
  });
});
