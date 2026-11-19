import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ChatContext, ChatResponse } from "@/entities/ai-chat";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { AiChatDock } from "./AiChatDock";

const SESSION_ID = "0b9f1c3e-6d0a-4b7e-9a51-1f2e3d4c5b6a";
const TRACE = "0123456789abcdef0123456789abcdef";
const BUTTON = "부활남 어디서 몇 시에 해?";
const EXPIRED = "이전 조건을 이어받지 못해 전체 기준으로 찾았어요";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const base: ChatResponse = {
  sessionId: SESSION_ID,
  answer: "",
  path: "NL2SQL",
  columns: null,
  rows: null,
  rowCount: 0,
  truncated: false,
  sql: null,
  presetId: null,
  asOf: "2026-11-19T19:42:00+09:00",
  llm: { calls: 1, inputTokens: 400, outputTokens: 60, cacheReadTokens: 3800, cacheWriteTokens: 0 },
  latencyMs: 1200,
  rejection: null,
  choices: null,
};
const context = (over: Partial<ChatContext>): ChatContext => ({
  id: "k7f2a1",
  date: "2026-11-20",
  theater: "부산본점",
  movie: null,
  label: "내일 11/20(금) · 부산본점",
  inherited: [],
  ...over,
});

/** "부산본점 내일 영화 뭐해?" 의 답 — 영화가 두 편이라 고르는 버튼이 뜬다 */
const listed = (answer: string, ctx?: ChatContext | null): ChatResponse => ({
  ...base,
  answer,
  columns: [
    { name: "movie_title", type: "text" },
    { name: "starts_at", type: "text" },
  ],
  rows: [
    ["부활남", "2026-11-20 14:00"],
    ["별이 지는 항구", "2026-11-20 16:30"],
  ],
  rowCount: 2,
  ...(ctx === undefined ? {} : { context: ctx }),
});
/** 영화 버튼의 답 — 시작 시각만 (ai 내부 틀의 표 열) */
const showtimes = (answer: string, rows: string[], ctx: ChatContext | null): ChatResponse => ({
  ...base,
  answer,
  path: "CACHE_EXACT",
  presetId: "ctx_movie_date_theater",
  columns: [{ name: "starts_at", type: "text" }],
  rows: rows.map((row) => [row]),
  rowCount: rows.length,
  llm: { calls: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
  latencyMs: 12,
  context: ctx,
});

const authenticated: Session = {
  ...unavailableSession,
  status: "authenticated",
  user: { id: "00000000-0000-4000-8000-000000000001", name: "한채연", email: null },
};

/** replies: 챗 요청 순서대로 돌려줄 응답(함수면 그때 만든다) */
function mockApi(...replies: (ChatResponse | (() => Response))[]) {
  let asked = 0;
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    if (url === "/api/ai/presets") return json(200, { items: [] });
    if (url.startsWith("/api/movies")) {
      return json(200, { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
    }
    const reply = replies[Math.min(asked++, replies.length - 1)];
    return typeof reply === "function" ? reply() : json(200, reply);
  });
}

const chatBodies = (mock: { mock: { calls: unknown[][] } }) =>
  mock.mock.calls
    .filter(([url]) => String(url) === "/api/ai/chat")
    .map(([, init]) => JSON.parse(String((init as RequestInit | undefined)?.body)));

async function openAndAsk(question: string) {
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
  await userEvent.type(await screen.findByRole("textbox", { name: "질문" }), `${question}{Enter}`);
}

/** n 번째 답의 「영화 고르기」에서 부활남을 누른다 */
async function pickMovie(nth = 0) {
  await waitFor(() => expect(screen.getAllByRole("group", { name: "영화 고르기" }).length).toBeGreaterThan(nth));
  const group = screen.getAllByRole("group", { name: "영화 고르기" })[nth];
  await waitFor(() => expect(within(group).getByRole("button", { name: "부활남" })).toBeEnabled());
  await userEvent.click(within(group).getByRole("button", { name: "부활남" }));
}

describe("문맥 이어받기 (3.4.22)", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it("영화 버튼은 그 답의 context.id 를 continueFrom 으로 보내고, 이어받은 답에 조건과 「조건 지우기」를 보여 준다", async () => {
    const fetchMock = mockApi(
      listed("내일 부산본점 상영 영화: 2건입니다.", context({})),
      showtimes(
        "내일 11/20(금) 부산본점 부활남 회차: 1건입니다.",
        ["2026-11-20 14:00"],
        context({ id: "p93x0d", movie: "부활남", inherited: ["date", "theater"] }),
      ),
      showtimes(
        "부활남 회차: 3건입니다.",
        ["2026-11-19 21:00", "2026-11-20 14:00", "2026-11-21 11:00"],
        context({ id: "c41e7b", date: null, theater: null, movie: "부활남", label: "부활남" }),
      ),
    );
    await openAndAsk("부산본점 내일 영화 뭐해?");
    await pickMovie();

    expect(await screen.findByText("내일 11/20(금) 부산본점 부활남 회차: 1건입니다.")).toBeInTheDocument();
    // 보내는 문장은 예전과 같고 continueFrom 만 더 간다
    expect(chatBodies(fetchMock)[1]).toEqual({ message: BUTTON, sessionId: SESSION_ID, continueFrom: "k7f2a1" });
    expect(screen.getByText("내일 11/20(금) · 부산본점 기준")).toBeInTheDocument();
    expect(screen.queryByText(EXPIRED)).not.toBeInTheDocument();

    // 조건 지우기 = 같은 질문을 continueFrom 없이 다시 보낸다
    await userEvent.click(screen.getByRole("button", { name: "내일 11/20(금) · 부산본점 조건 지우기" }));
    expect(await screen.findByText("부활남 회차: 3건입니다.")).toBeInTheDocument();
    expect(chatBodies(fetchMock)[2]).toEqual({ message: BUTTON, sessionId: SESSION_ID });
    // 다시 받은 답은 이어받지 않은 답 — 조건 표시도 만료 안내도 없다
    expect(screen.getAllByText(/ 기준$/, { selector: "span" })).toHaveLength(1);
    expect(screen.queryByText(EXPIRED)).not.toBeInTheDocument();
  });

  it("continueFrom 을 보냈는데 이어받지 못한 답에는 만료 안내를 보여 준다", async () => {
    const fetchMock = mockApi(
      listed("내일 부산본점 상영 영화: 2건입니다.", context({})),
      // 세션 30분 만료 — ai 가 독립 질문으로 답했다(inherited 빈 배열)
      showtimes(
        "부활남 회차: 3건입니다.",
        ["2026-11-19 21:00", "2026-11-20 14:00", "2026-11-21 11:00"],
        context({ id: "c41e7b", date: null, theater: null, movie: "부활남", label: "부활남" }),
      ),
    );
    await openAndAsk("부산본점 내일 영화 뭐해?");
    await pickMovie();

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(chatBodies(fetchMock)[1].continueFrom).toBe("k7f2a1");
    expect(screen.queryByRole("button", { name: /조건 지우기/ })).not.toBeInTheDocument();
  });

  it("context 가 없는 옛 ai 응답이면 예전과 같다 — continueFrom 도 조건 표시도 안내도 없다", async () => {
    const withoutContext = showtimes("부활남 회차: 1건입니다.", ["2026-11-19 21:00"], null);
    delete withoutContext.context;
    const fetchMock = mockApi(listed("상영 영화: 2건입니다."), withoutContext);
    await openAndAsk("상영 중인 영화 알려줘");
    await pickMovie();

    expect(await screen.findByText("부활남 회차: 1건입니다.")).toBeInTheDocument();
    expect(chatBodies(fetchMock)[1]).toEqual({ message: BUTTON, sessionId: SESSION_ID });
    expect(screen.queryByText(/ 기준$/, { selector: "span" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /조건 지우기/ })).not.toBeInTheDocument();
    expect(screen.queryByText(EXPIRED)).not.toBeInTheDocument();
  });

  it("조건이 없는 답(context null)의 영화 버튼도 continueFrom 을 보내지 않는다", async () => {
    const fetchMock = mockApi(listed("상영 영화: 2건입니다.", null), showtimes("부활남 회차: 1건입니다.", ["2026-11-19 21:00"], null));
    await openAndAsk("상영 중인 영화 알려줘");
    await pickMovie();
    expect(await screen.findByText("부활남 회차: 1건입니다.")).toBeInTheDocument();
    expect(chatBodies(fetchMock)[1]).toEqual({ message: BUTTON, sessionId: SESSION_ID });
    expect(screen.queryByText(EXPIRED)).not.toBeInTheDocument();
  });

  it("영화만 있는 답(이어받을 날짜·극장이 없음)의 버튼은 continueFrom 을 보내지 않고 안내도 없다", async () => {
    const onlyMovie = context({ date: null, theater: null, movie: "부활남", label: "부활남" });
    const fetchMock = mockApi(
      listed("부활남과 같이 볼 영화: 2건입니다.", onlyMovie),
      showtimes("부활남 회차: 1건입니다.", ["2026-11-19 21:00"], context({ ...onlyMovie, id: "c41e7b" })),
    );
    await openAndAsk("부활남이랑 같이 볼 영화");
    await pickMovie();
    expect(await screen.findByText("부활남 회차: 1건입니다.")).toBeInTheDocument();
    expect(chatBodies(fetchMock)[1]).toEqual({ message: BUTTON, sessionId: SESSION_ID });
    expect(screen.queryByText(EXPIRED)).not.toBeInTheDocument();
  });

  it("위쪽(예전) 답의 영화 버튼은 직전 답이 아니라 그 답의 id 를 보낸다", async () => {
    const fetchMock = mockApi(
      listed("내일 부산본점 상영 영화: 2건입니다.", context({ id: "older1" })),
      listed("오늘 월드타워 상영 영화: 2건입니다.", context({ id: "newer2", theater: "월드타워", label: "오늘 11/19(목) · 월드타워" })),
      showtimes(
        "내일 11/20(금) 부산본점 부활남 회차: 1건입니다.",
        ["2026-11-20 14:00"],
        context({ id: "p93x0d", movie: "부활남", inherited: ["date", "theater"] }),
      ),
    );
    await openAndAsk("부산본점 내일 영화 뭐해?");
    expect(await screen.findByText("내일 부산본점 상영 영화: 2건입니다.")).toBeInTheDocument();
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "오늘 월드타워 영화 뭐해?{Enter}");
    expect(await screen.findByText("오늘 월드타워 상영 영화: 2건입니다.")).toBeInTheDocument();

    await pickMovie(0); // 첫 번째(위쪽) 답의 버튼
    await waitFor(() => expect(chatBodies(fetchMock)).toHaveLength(3));
    expect(chatBodies(fetchMock)[2]).toEqual({ message: BUTTON, sessionId: SESSION_ID, continueFrom: "older1" });
  });

  it("영화 버튼 요청이 실패한 뒤 「다시 묻기」도 continueFrom 을 그대로 보낸다", async () => {
    const fetchMock = mockApi(
      listed("내일 부산본점 상영 영화: 2건입니다.", context({})),
      () =>
        json(503, {
          code: "AI_LLM_UNAVAILABLE",
          message: "x",
          traceId: TRACE,
          details: [{ field: "provider", value: "anthropic", reason: "OVERLOADED" }],
        }),
      showtimes(
        "내일 11/20(금) 부산본점 부활남 회차: 1건입니다.",
        ["2026-11-20 14:00"],
        context({ id: "p93x0d", movie: "부활남", inherited: ["date", "theater"] }),
      ),
    );
    await openAndAsk("부산본점 내일 영화 뭐해?");
    await pickMovie();
    await userEvent.click(await screen.findByRole("button", { name: /다시 묻기/ }));
    expect(await screen.findByText("내일 11/20(금) · 부산본점 기준")).toBeInTheDocument();
    expect(chatBodies(fetchMock)[2]).toEqual({ message: BUTTON, sessionId: SESSION_ID, continueFrom: "k7f2a1" });
  });
});
