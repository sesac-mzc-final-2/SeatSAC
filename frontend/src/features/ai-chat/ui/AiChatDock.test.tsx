import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { AiChatDock } from "./AiChatDock";
import nl2sql from "../../../../../contracts/openapi/examples/ai/chat-nl2sql.json";
import cacheSemantic from "../../../../../contracts/openapi/examples/ai/chat-cache-semantic.json";
import clarify from "../../../../../contracts/openapi/examples/ai/chat-clarify.json";

const TRACE = "0123456789abcdef0123456789abcdef";

// 좌석 잡기 응답 예시 (3.4.19). 계약 예시 contracts/openapi/examples/ai/chat-hold-*.json 은 ai 쪽 PR 에 있어
// 이 PR 만으로도 테스트가 돌도록 같은 값을 여기에 둔다 — 계약 PR 이 머지되면 그 파일을 import 하도록 바꾼다
const holdConfirm = {
  value: {
    "sessionId": "0b9f1c3e-6d0a-4b7e-9a51-1f2e3d4c5b6a",
    "answer": "별의 항해 11/17(화) 20:00 건대입구 1관 J7·J8 — 2석. 이 좌석을 5분간 잡을까요? (2분 안에 눌러 주세요)",
    "path": "HOLD_CONFIRM",
    "columns": null,
    "rows": null,
    "rowCount": 0,
    "truncated": false,
    "sql": null,
    "presetId": null,
    "asOf": "2026-11-17T19:40:00+09:00",
    "llm": {
      "calls": 1,
      "inputTokens": 420,
      "outputTokens": 48,
      "cacheReadTokens": 4100,
      "cacheWriteTokens": 0
    },
    "latencyMs": 1480,
    "rejection": null,
    "choices": [
      {
        "label": "J7·J8 잡기",
        "message": "좌석 잡기 #c7f2a1"
      },
      {
        "label": "취소",
        "message": "취소 #c7f2a1"
      }
    ],
    "seatHold": {
      "screeningId": 5123,
      "bookingId": null,
      "seatLabels": [
        "J7",
        "J8"
      ],
      "amount": null,
      "expiresAt": null,
      "confirmExpiresAt": "2026-11-17T19:42:00+09:00",
      "reason": null
    }
  },
};
const holdResult = {
  value: {
    "sessionId": "0b9f1c3e-6d0a-4b7e-9a51-1f2e3d4c5b6a",
    "answer": "J7·J8 좌석을 잡았습니다. 19:45까지 결제를 마쳐 주세요.",
    "path": "HOLD_RESULT",
    "columns": null,
    "rows": null,
    "rowCount": 0,
    "truncated": false,
    "sql": null,
    "presetId": null,
    "asOf": "2026-11-17T19:40:00+09:00",
    "llm": {
      "calls": 0,
      "inputTokens": 0,
      "outputTokens": 0,
      "cacheReadTokens": 0,
      "cacheWriteTokens": 0
    },
    "latencyMs": 210,
    "rejection": null,
    "choices": null,
    "seatHold": {
      "screeningId": 5123,
      "bookingId": 9876,
      "seatLabels": [
        "J7",
        "J8"
      ],
      "amount": 30000,
      "expiresAt": "2026-11-17T19:45:05+09:00",
      "confirmExpiresAt": null,
      "reason": null
    }
  },
};
const holdGuide = {
  value: {
    "sessionId": "0b9f1c3e-6d0a-4b7e-9a51-1f2e3d4c5b6a",
    "answer": "J8 좌석은 이미 다른 분이 잡았어요. 좌석 화면에서 직접 골라 주세요.",
    "path": "HOLD_GUIDE",
    "columns": null,
    "rows": null,
    "rowCount": 0,
    "truncated": false,
    "sql": null,
    "presetId": null,
    "asOf": "2026-11-17T19:40:00+09:00",
    "llm": {
      "calls": 1,
      "inputTokens": 420,
      "outputTokens": 48,
      "cacheReadTokens": 4100,
      "cacheWriteTokens": 0
    },
    "latencyMs": 1390,
    "rejection": null,
    "choices": null,
    "seatHold": {
      "screeningId": 5123,
      "bookingId": null,
      "seatLabels": [
        "J7",
        "J8"
      ],
      "amount": null,
      "expiresAt": null,
      "confirmExpiresAt": null,
      "reason": "SEAT_UNAVAILABLE"
    }
  },
};
/** 챗봇 질문 요청 본문만 (영화 목록 등 다른 요청은 뺀다) */
const chatBodies = (mock: { mock: { calls: unknown[][] } }) =>
  mock.mock.calls
    .filter(([url]) => String(url) === "/api/ai/chat")
    .map(([, init]) => JSON.parse(String((init as RequestInit | undefined)?.body)));
const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
const presets = {
  items: [{ presetId: "today_after_time", title: "오늘 ○시 이후 회차", example: "오늘 20시 이후에 시작하는 영화 알려줘" }],
};

const authenticated: Session = {
  ...unavailableSession,
  status: "authenticated",
  user: { id: "00000000-0000-4000-8000-000000000001", name: "한채연", email: null, groups: [] },
};

function renderDock(session: Session = authenticated) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={session}>
        <MemoryRouter>
          <AiChatDock />
        </MemoryRouter>
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
}

describe("AiChatDock (3.3.7)", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    window.localStorage.clear();
  });

  it("로그인 전에는 아무것도 부르지 않고 보이지 않는다", () => {
    const fetchMock = vi.spyOn(globalThis, "fetch");
    renderDock({ ...unavailableSession, status: "anonymous" });
    expect(screen.queryByRole("button", { name: /AI 상영 도우미/ })).not.toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("로그인 직후에는 /api/ai 를 부르지 않는다 — 창을 처음 열 때 presets 를 부른다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(json(200, presets));
    renderDock();
    expect(screen.getByRole("button", { name: "AI 상영 도우미 열기" })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "AI 상영 도우미 열기" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith("/api/ai/presets", expect.anything()));
    expect(await screen.findByRole("button", { name: "오늘 ○시 이후 회차" })).toBeInTheDocument();
  });

  it("presets 응답을 기다리는 동안은 입력은 받되 보내지 않는다 — 플래그 off 인지 아직 모른다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockReturnValue(new Promise(() => {}));
    renderDock();
    await userEvent.click(screen.getByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "오늘 20시 이후{Enter}");
    expect(screen.getByRole("textbox", { name: "질문" })).toHaveValue("오늘 20시 이후");
    expect(screen.getByRole("button", { name: "보내기" })).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith("/api/ai/presets", expect.anything());
  });

  it("추천 질문이 404(플래그 off)면 준비 중 안내, 닫으면 버튼을 숨긴다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      json(404, { code: "NOT_FOUND", message: "x", traceId: TRACE, details: [] }),
    );
    renderDock();
    await userEvent.click(screen.getByRole("button", { name: "AI 상영 도우미 열기" }));
    expect(await screen.findByText("AI 상영 도우미는 아직 준비 중이에요")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "질문" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "닫기" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /AI 상영 도우미/ })).not.toBeInTheDocument();
  });

  it("503(ai 파드 없음)이면 버튼은 두고 창 안에서 비활성 안내", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      json(503, { code: "SERVICE_UNAVAILABLE", message: "x", traceId: TRACE, details: [] }),
    );
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    expect(await screen.findByText("AI 챗봇이 지금은 쉬고 있어요")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "질문" })).toBeDisabled();
  });

  it("시각 빈칸 추천 질문은 입력창에 채우기만 하고, 보내면 답·결과 표·기준 시각을 보여 준다. 다음 질문엔 sessionId 를 붙인다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/ai/presets") return json(200, presets);
      if (url.startsWith("/api/movies")) return json(200, { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
      return json(200, chatBodies(fetchMock).length <= 1 ? cacheSemantic.value : nl2sql.value);
    });
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.click(await screen.findByRole("button", { name: "오늘 ○시 이후 회차" }));

    // 바로 보내지 않는다 — 지금 시각 다음 정시로 채우고 사용자가 고쳐서 보낸다
    const input = screen.getByRole<HTMLTextAreaElement>("textbox", { name: "질문" });
    await waitFor(() => expect(input.value).toMatch(/^오늘 \d{1,2}시 이후에 시작하는 영화 알려줘$/));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await userEvent.clear(input);
    await userEvent.type(input, "오늘 20시 이후에 시작하는 영화 알려줘{Enter}");

    expect(await screen.findByText(cacheSemantic.value.answer)).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "영화" })).toBeInTheDocument();
    // 시각 칸은 회차 상태(링크·마감)에 따라 이름이 달라져 글자로 찾는다 — 실제 시계에 기대지 않게
    expect(screen.getByText("11/17 20:10")).toBeInTheDocument();
    expect(screen.getByText(/19:42 기준/)).toBeInTheDocument();
    expect(chatBodies(fetchMock)[0]).toEqual({ message: "오늘 20시 이후에 시작하는 영화 알려줘" });

    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "매진 임박한 회차{Enter}");
    expect(await screen.findByText(nl2sql.value.answer)).toBeInTheDocument();
    expect(chatBodies(fetchMock)[1]).toEqual({
      message: "매진 임박한 회차",
      sessionId: cacheSemantic.value.sessionId,
    });
  });

  it("되묻기(CLARIFY)면 날짜 버튼을 보여 주고, 누르면 그 질문을 같은 sessionId 로 보낸다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/ai/presets") return json(200, presets);
      return json(200, fetchMock.mock.calls.length <= 2 ? clarify.value : nl2sql.value);
    });
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.type(await screen.findByRole("textbox", { name: "질문" }), "잔여석 많은 회차{Enter}");

    expect(await screen.findByText(clarify.value.answer)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "내일 11/18(수)" }));

    expect(await screen.findByText(nl2sql.value.answer)).toBeInTheDocument();
    const [, init] = fetchMock.mock.calls[2];
    expect(JSON.parse(String(init?.body))).toEqual({
      message: "내일 잔여석 많은 회차",
      sessionId: clarify.value.sessionId,
    });
  });

  /** 확인 요청 예시 — 확인 기한은 지금부터 afterMs 뒤(예시의 고정 시각은 이미 지났다) */
  const confirmRequest = (afterMs = 120_000, id = "c7f2a1", seats = ["J7", "J8"]) => ({
    ...holdConfirm.value,
    choices: [
      { label: `${seats.join("·")} 잡기`, message: `좌석 잡기 #${id}` },
      { label: "취소", message: `취소 #${id}` },
    ],
    seatHold: {
      ...holdConfirm.value.seatHold,
      seatLabels: seats,
      confirmExpiresAt: new Date(Date.now() + afterMs).toISOString(),
    },
  });
  /** 좌석 잡기 예시의 시각(확인 19:42 · 결제 19:45:05)이 아직 오지 않은 때로 시계를 맞춘다 — Date 만, 타이머는 그대로 */
  const HOLD_NOW = "2026-11-17T19:40:00+09:00";
  const freezeClock = () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date(HOLD_NOW));
  };
  /**
   * 기한을 직접 넘기는 테스트용 — 타이머까지 가짜로 두고(실제 시간이 흐르는 만큼은 같이 흐른다:
   * userEvent·findBy 가 그대로 돈다) `passTime` 으로 기한을 건너뛴다. 실제로 기다리지 않는다
   */
  const fakeClock = () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(HOLD_NOW));
  };
  const passTime = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));
  /** 회차 고르기 예시 — 회차 버튼도 확인 버튼과 같은 기한(confirmExpiresAt)을 받는다 */
  const screeningChoices = (afterMs = 120_000) => ({
    ...holdGuide.value,
    answer: "어느 회차인가요? 골라 주세요.",
    choices: [
      { label: "별의 항해 11/17(화) 20:00 건대입구 1관", message: "별의 항해 11/17(화) 20:00 건대입구 1관" },
      { label: "한강의 밤 11/17(화) 21:00 건대입구 2관", message: "한강의 밤 11/17(화) 21:00 건대입구 2관" },
    ],
    seatHold: {
      ...holdGuide.value.seatHold,
      screeningId: null,
      reason: "SCREENING_AMBIGUOUS",
      confirmExpiresAt: new Date(Date.now() + afterMs).toISOString(),
    },
  });
  const gatewayTimeout = () => json(504, { code: "GATEWAY_TIMEOUT", message: "x", traceId: TRACE, details: [] });
  const openAndAsk = async (question: string) => {
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.type(await screen.findByRole("textbox", { name: "질문" }), `${question}{Enter}`);
  };

  it("좌석 잡기: 확인 버튼을 누르면 확인 ID가 실린 문장을 같은 sessionId 로 보내고, 결제 링크를 누르면 창이 닫힌다", async () => {
    freezeClock();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      return json(200, chatBodies(fetchMock).length <= 1 ? confirmRequest() : holdResult.value);
    });
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");

    // 확인 요청: 아직 결제 링크가 없다 — 확인(좌석이 적힌 주 버튼)·취소 버튼만
    expect(await screen.findByText(holdConfirm.value.answer)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /결제하러 가기/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "취소" })).toBeEnabled();
    expect(chatBodies(fetchMock)).toHaveLength(1);
    await userEvent.click(screen.getByRole("button", { name: "J7·J8 잡기" }));

    expect(await screen.findByText(holdResult.value.answer)).toBeInTheDocument();
    expect(chatBodies(fetchMock)[1]).toEqual({ message: "좌석 잡기 #c7f2a1", sessionId: holdConfirm.value.sessionId });
    const card = screen.getByLabelText("잡은 좌석");
    expect(within(card).getByText("J7, J8")).toBeInTheDocument();
    expect(within(card).getByText("30,000원")).toBeInTheDocument();
    expect(within(card).getByText("19:45까지")).toBeInTheDocument();
    // 지난 확인 요청의 버튼은 다시 누를 수 없다
    expect(screen.getByRole("button", { name: "J7·J8 잡기" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "취소" })).toBeDisabled();

    // 결제 링크에 결제 기한이 같이 보인다
    const pay = screen.getByRole("link", { name: /결제하러 가기 \(19:45까지\)/ });
    expect(pay).toHaveAttribute("href", "/bookings/9876/pay");
    await userEvent.click(pay);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(chatBodies(fetchMock)).toHaveLength(2);
  });

  it("좌석 잡기: 새 확인 요청(B)이 오면 앞선 확인(A)의 버튼과 A의 실패한 「다시 묻기」는 눌리지 않는다", async () => {
    const a = confirmRequest(120_000, "aaaaaa", ["J7", "J8"]);
    const b = confirmRequest(120_000, "bbbbbb", ["A1"]);
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      const sent = chatBodies(fetchMock).length;
      if (sent === 1) return json(200, a);
      // A의 확인이 서버 오류로 실패한다 → 오류 항목에 「다시 묻기」
      if (sent === 2) return json(503, { code: "SERVICE_UNAVAILABLE", message: "x", traceId: TRACE, details: [] });
      return json(200, b);
    });
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");
    await userEvent.click(await screen.findByRole("button", { name: "J7·J8 잡기" }));
    // A가 아직 최신 확인 요청일 때는 다시 물을 수 있다
    expect(await screen.findByRole("button", { name: "다시 묻기" })).toBeInTheDocument();

    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "오늘 20시 건대입구 A1 잡아줘{Enter}");
    expect(await screen.findByRole("button", { name: "A1 잡기" })).toBeEnabled();
    // 이제 A의 것은 전부 막힌다 — A의 「다시 묻기」가 B를 확정할 수 없다
    expect(screen.getByRole("button", { name: "J7·J8 잡기" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "다시 묻기" })).not.toBeInTheDocument();
    expect(chatBodies(fetchMock).map((body) => body.message)).toEqual([
      "오늘 20시 건대입구 J7 J8 잡아줘",
      "좌석 잡기 #aaaaaa",
      "오늘 20시 건대입구 A1 잡아줘",
    ]);
  });

  it("좌석 잡기: 확인 시간이 지나면 버튼을 막고 안내한다", async () => {
    fakeClock();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
      String(input) === "/api/ai/presets" ? json(200, presets) : json(200, confirmRequest()),
    );
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");
    expect(await screen.findByRole("button", { name: "J7·J8 잡기" })).toBeEnabled();
    expect(screen.queryByText(/확인 시간이 지났어요/)).not.toBeInTheDocument();
    // 기한 직전까지는 그대로
    await passTime(110_000);
    expect(screen.getByRole("button", { name: "J7·J8 잡기" })).toBeEnabled();
    await passTime(11_000);
    // 만료 안내는 화면 낭독기에도 알린다
    expect(screen.getByText(/확인 시간이 지났어요/)).toHaveAttribute("role", "status");
    expect(screen.getByRole("button", { name: "J7·J8 잡기" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "취소" })).toBeDisabled();
    expect(chatBodies(fetchMock)).toHaveLength(1);
  });

  it("좌석 잡기: 버튼 문장의 모양이 달라도 확인 요청(path·기한)이면 기한 뒤·지난 요청의 버튼을 막는다", async () => {
    fakeClock();
    // 서버가 버튼 문장 형식을 바꿨다고 쳐도(ID 자리수·글자) 안전장치는 응답 값으로 건다
    const odd = (id: string) => ({
      ...confirmRequest(),
      choices: [
        { label: "J7·J8 잡기", message: `hold-confirm:${id}` },
        { label: "취소", message: `hold-cancel:${id}` },
      ],
    });
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      return json(200, chatBodies(fetchMock).length <= 1 ? odd("A") : nl2sql.value);
    });
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");
    expect(await screen.findByRole("button", { name: "J7·J8 잡기" })).toBeEnabled();
    await passTime(121_000);
    expect(screen.getByRole("button", { name: "J7·J8 잡기" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "취소" })).toBeDisabled();
    expect(screen.getByText(/확인 시간이 지났어요/)).toHaveAttribute("role", "status");
    // 다른 답이 온 뒤에도(지난 요청) 막힌 채다
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "오늘 상영작{Enter}");
    expect(await screen.findByText(nl2sql.value.answer)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "J7·J8 잡기" })).toBeDisabled();
    expect(chatBodies(fetchMock)).toHaveLength(2);
  });

  it("좌석 잡기: 회차 고르기 버튼도 기한(confirmExpiresAt)이 지나면 막고 안내한다 — 기한 안에는 그 회차를 보낸다", async () => {
    fakeClock();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      const sent = chatBodies(fetchMock).length;
      if (sent === 1) return json(200, screeningChoices());
      if (sent === 2) return json(200, confirmRequest());
      return json(200, screeningChoices());
    });
    await openAndAsk("오늘 건대 J7 J8 잡아줘");
    const first = await screen.findByRole("button", { name: "별의 항해 11/17(화) 20:00 건대입구 1관" });
    expect(first).toBeEnabled();
    await userEvent.click(first);
    expect(await screen.findByRole("button", { name: "J7·J8 잡기" })).toBeEnabled();
    expect(chatBodies(fetchMock)[1]).toEqual({
      message: "별의 항해 11/17(화) 20:00 건대입구 1관",
      sessionId: holdGuide.value.sessionId,
    });
    // 확인 요청이 온 뒤에는 앞의 회차 버튼이 지난 것이다
    expect(screen.getByRole("button", { name: "한강의 밤 11/17(화) 21:00 건대입구 2관" })).toBeDisabled();

    // 다시 회차를 고르게 됐는데 2분이 지나면 — 버튼을 막고 안내한다
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "오늘 건대 J7 J8 잡아줘{Enter}");
    await waitFor(() => expect(chatBodies(fetchMock)).toHaveLength(3));
    await waitFor(() =>
      expect(screen.getAllByRole("button", { name: "한강의 밤 11/17(화) 21:00 건대입구 2관" })[1]).toBeEnabled(),
    );
    await passTime(121_000);
    for (const button of screen.getAllByRole("button", { name: /건대입구 [12]관$/ })) expect(button).toBeDisabled();
    expect(screen.getByText(/확인 시간이 지났어요/)).toHaveAttribute("role", "status");
    expect(chatBodies(fetchMock)).toHaveLength(3);
  });

  it("좌석 잡기: 확인이 응답만 실패하면(시간 초과) 「다시 묻기」가 같은 확인 문장을 보내고, 결과가 오면 결제 카드를 보여 준다", async () => {
    freezeClock();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      const sent = chatBodies(fetchMock).length;
      if (sent === 1) return json(200, confirmRequest());
      // 확인은 서버에 닿아 선점까지 끝났지만 화면은 gateway 시간 초과를 본다
      if (sent === 2) return gatewayTimeout();
      // 같은 확인 ID가 다시 오면 ai 가 같은 결과를 돌려준다(booking 을 다시 부르지 않는다)
      return json(200, holdResult.value);
    });
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");
    await userEvent.click(await screen.findByRole("button", { name: "J7·J8 잡기" }));
    await userEvent.click(await screen.findByRole("button", { name: "다시 묻기" }));

    expect(await screen.findByText(holdResult.value.answer)).toBeInTheDocument();
    const sent = chatBodies(fetchMock);
    expect(sent).toHaveLength(3);
    expect(sent[2]).toEqual({ message: "좌석 잡기 #c7f2a1", sessionId: holdConfirm.value.sessionId });
    expect(sent[2]).toEqual(sent[1]);
    expect(within(screen.getByLabelText("잡은 좌석")).getByText("J7, J8")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /결제하러 가기/ })).toHaveAttribute("href", "/bookings/9876/pay");
    // 결과가 온 뒤에는 그 확인의 버튼·「다시 묻기」가 지난 것이다
    expect(screen.getByRole("button", { name: "J7·J8 잡기" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "다시 묻기" })).not.toBeInTheDocument();
  });

  it("좌석 잡기: 실패한 확인의 「다시 묻기」도 확인 시간이 지나면 사라지고 같은 안내를 보여 준다", async () => {
    fakeClock();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      return chatBodies(fetchMock).length === 1 ? json(200, confirmRequest()) : gatewayTimeout();
    });
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");
    await userEvent.click(await screen.findByRole("button", { name: "J7·J8 잡기" }));
    expect(await screen.findByRole("button", { name: "다시 묻기" })).toBeInTheDocument();
    expect(screen.queryByText(/확인 시간이 지났어요/)).not.toBeInTheDocument();

    await passTime(121_000);
    expect(screen.queryByRole("button", { name: "다시 묻기" })).not.toBeInTheDocument();
    // 확인 요청의 답과 오류 항목 양쪽에 같은 안내
    const notes = screen.getAllByText("확인 시간이 지났어요. 잡을 회차와 좌석을 다시 말해 주세요.");
    expect(notes).toHaveLength(2);
    for (const note of notes) expect(note).toHaveAttribute("role", "status");
    expect(notes.filter((note) => note.closest('[role="alert"]'))).toHaveLength(1);
    expect(chatBodies(fetchMock)).toHaveLength(2);
  });

  it("좌석 잡기: 결제 기한이 지나면 「결제하러 가기」를 숨기고 안내한다", async () => {
    fakeClock();
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      return json(200, chatBodies(fetchMock).length <= 1 ? confirmRequest() : holdResult.value);
    });
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");
    await userEvent.click(await screen.findByRole("button", { name: "J7·J8 잡기" }));
    expect(await screen.findByRole("link", { name: /결제하러 가기 \(19:45까지\)/ })).toBeInTheDocument();
    expect(screen.queryByText(/결제 시간이 지났어요/)).not.toBeInTheDocument();

    // 결제 기한 19:45:05 — 지금 19:40 무렵
    await passTime(4 * 60_000);
    expect(screen.getByRole("link", { name: /결제하러 가기/ })).toBeInTheDocument();
    await passTime(70_000);
    expect(screen.queryByRole("link", { name: /결제하러 가기/ })).not.toBeInTheDocument();
    expect(screen.getByText(/결제 시간이 지났어요/)).toHaveAttribute("role", "status");
    // 잡았던 좌석 요약은 남는다
    expect(within(screen.getByLabelText("잡은 좌석")).getByText("J7, J8")).toBeInTheDocument();
  });

  it("좌석 잡기: 진행 중인 예매가 있다는데 예매 ID가 없으면 링크 없이 안내만, 있으면 그 예매로 가는 링크", async () => {
    freezeClock();
    const activeHoldExists = (bookingId: number | null, expiresAt: string | null) => ({
      ...holdResult.value,
      answer: "이미 진행 중인 예매(선점)가 있어요. 그 예매를 먼저 결제하거나 취소해 주세요.",
      seatHold: { ...holdResult.value.seatHold, bookingId, seatLabels: [], amount: null, expiresAt, reason: "ACTIVE_HOLD_EXISTS" },
    });
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/api/ai/presets") return json(200, presets);
      const sent = chatBodies(fetchMock).length;
      if (sent % 2 === 1) return json(200, confirmRequest(120_000, sent === 1 ? "aaaaaa" : "bbbbbb"));
      return json(200, sent === 2 ? activeHoldExists(null, null) : activeHoldExists(9001, "2026-11-17T19:44:00+09:00"));
    });
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");
    await userEvent.click(await screen.findByRole("button", { name: "J7·J8 잡기" }));
    expect(await screen.findByText(/이미 진행 중인 예매/)).toBeInTheDocument();
    // booking 이 예매 ID를 알려 주지 않았다 — /bookings/null/pay 같은 링크를 만들지 않는다
    expect(screen.queryByRole("link", { name: /진행 중인 예매로 가기/ })).not.toBeInTheDocument();
    expect(document.querySelector('a[href*="null"]')).toBeNull();
    expect(screen.getByText("진행 중인 예매는 마이페이지 예매 내역에서 확인해 주세요.")).toBeInTheDocument();
    expect(screen.queryByLabelText("잡은 좌석")).not.toBeInTheDocument();

    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "오늘 20시 건대입구 J7 J8 잡아줘{Enter}");
    await waitFor(() => expect(screen.getAllByRole("button", { name: "J7·J8 잡기" })[1]).toBeEnabled());
    await userEvent.click(screen.getAllByRole("button", { name: "J7·J8 잡기" })[1]);
    const link = await screen.findByRole("link", { name: /진행 중인 예매로 가기 \(19:44까지\)/ });
    expect(link).toHaveAttribute("href", "/bookings/9001/pay");
  });

  it("좌석 잡기: 못 잡는 좌석이면 좌석 화면 링크를 보여 주고, 누르면 창이 닫힌다", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
      String(input) === "/api/ai/presets" ? json(200, presets) : json(200, holdGuide.value),
    );
    await openAndAsk("오늘 20시 건대입구 J7 J8 잡아줘");
    expect(await screen.findByText(holdGuide.value.answer)).toBeInTheDocument();
    expect(screen.queryByLabelText("잡은 좌석")).not.toBeInTheDocument();
    const seats = screen.getByRole("link", { name: /좌석 화면에서 고르기/ });
    expect(seats).toHaveAttribute("href", "/screenings/5123");
    await userEvent.click(seats);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("데모 정보를 켜면 경로·토큰을 보여 준다", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
      String(input) === "/api/ai/presets" ? json(200, presets) : json(200, cacheSemantic.value),
    );
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.click(screen.getByRole("button", { name: "데모 정보" }));
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "오늘 20시 이후{Enter}");
    expect(await screen.findByText("의미 캐시")).toBeInTheDocument();
    expect(screen.getByText("Claude 호출 없음 · 토큰 0")).toBeInTheDocument();
    expect(screen.getByText("SQL 보기")).toBeInTheDocument();
  });

  it("Escape 로 창을 닫아도 작성 중인 질문은 남는다", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(json(200, presets));
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "내일 영등포");
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "AI 상영 도우미 열기" }));
    expect(screen.getByRole("textbox", { name: "질문" })).toHaveValue("내일 영등포");
  });

  it("429 분 한도면 안내하고 입력을 잠시 막는다", async () => {
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
      String(input) === "/api/ai/presets"
        ? json(200, presets)
        : json(
            429,
            {
              code: "AI_QUOTA_EXCEEDED",
              message: "x",
              traceId: TRACE,
              details: [{ field: "quota", value: "10/min", reason: "PER_MINUTE" }],
            },
            { "Retry-After": "15" },
          ),
    );
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "안녕{Enter}");
    expect(await screen.findByText(/15초 뒤에 다시 물어봐 주세요/)).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "질문" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "다시 묻기" })).not.toBeInTheDocument();
  });

  it("극장 편성표는 극장을 고르게 하고, 고르면 묻고 상영시간표 바로가기를 붙인다", async () => {
    const withTheater = {
      items: [...presets.items, { presetId: "theater_schedule", title: "극장 편성표", example: "내일 건대입구 편성표 보여줘" }],
    };
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/ai/presets") return json(200, withTheater);
      if (url.startsWith("/api/theaters")) {
        return json(200, { items: [{ theaterId: 7, name: "건대입구", region: "서울", hasLayout: true }] });
      }
      return json(200, cacheSemantic.value);
    });
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.click(await screen.findByRole("button", { name: "극장 편성표" }));

    const picker = await screen.findByRole("group", { name: "어느 극장의 편성표를 볼까요?" });
    await userEvent.click(await within(picker).findByRole("button", { name: "건대입구" }));

    expect(await screen.findByText(cacheSemantic.value.answer)).toBeInTheDocument();
    const chat = fetchMock.mock.calls.find(([url]) => String(url) === "/api/ai/chat");
    expect(JSON.parse(String(chat?.[1]?.body))).toEqual({ message: "오늘 건대입구 편성표 보여줘" });
    const link = screen.getByRole("link", { name: /건대입구 상영시간표 바로가기/ });
    expect(link.getAttribute("href")).toMatch(/^\/showtimes\?theaterId=7&date=\d{4}-\d{2}-\d{2}$/);
  });

  it("고르는 말풍선을 Escape 로 닫으면 입력창에 초점이 가고, 한 번 더 누르면 창이 닫힌다", async () => {
    const withTheater = {
      items: [...presets.items, { presetId: "theater_schedule", title: "극장 편성표", example: "내일 건대입구 편성표 보여줘" }],
    };
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/ai/presets") return json(200, withTheater);
      return json(200, { items: [{ theaterId: 7, name: "건대입구", region: "서울", hasLayout: true }] });
    });
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.click(await screen.findByRole("button", { name: "극장 편성표" }));
    const name = "어느 극장의 편성표를 볼까요?";
    await within(await screen.findByRole("group", { name })).findByRole("button", { name: "건대입구" });

    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("group", { name })).not.toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument(); // 말풍선만 닫힌다
    expect(screen.getByRole("textbox", { name: "질문" })).toHaveFocus();

    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("고를 목록이 비어 있으면 안내한다", async () => {
    const withTheater = {
      items: [...presets.items, { presetId: "theater_schedule", title: "극장 편성표", example: "내일 건대입구 편성표 보여줘" }],
    };
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/ai/presets") return json(200, withTheater);
      return json(200, { items: [] });
    });
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.click(await screen.findByRole("button", { name: "극장 편성표" }));
    expect(await screen.findByText("고를 수 있는 항목이 없어요. 입력창에 직접 물어봐 주세요.")).toBeInTheDocument();
  });

  it("아래 추천 질문 줄은 3개와 「…」만 보이고, 「…」를 누르면 나머지를 고른다", async () => {
    const many = {
      items: ["a", "b", "c", "d", "e"].map((id) => ({ presetId: `x_${id}`, title: `질문 ${id}`, example: `예시 ${id}` })),
    };
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) =>
      String(input) === "/api/ai/presets" ? json(200, many) : json(200, cacheSemantic.value),
    );
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.type(await screen.findByRole("textbox", { name: "질문" }), "아무 질문{Enter}");
    await screen.findByText(cacheSemantic.value.answer);

    const bar = screen.getAllByRole("group", { name: "추천 질문" }).at(-1)!;
    expect(within(bar).getAllByRole("button").map((b) => b.textContent)).toEqual(["질문 a", "질문 b", "질문 c", "…"]);
    await userEvent.click(within(bar).getByRole("button", { name: "추천 질문 더 보기" }));
    const more = await screen.findByRole("group", { name: "무엇을 물어볼까요?" });
    await userEvent.click(within(more).getByRole("button", { name: "질문 e" }));
    await waitFor(() => expect(chatBodies(fetchMock)).toHaveLength(2));
    expect(chatBodies(fetchMock)[1].message).toBe("예시 e");
  });

  it("회차 목록 답은 시작 시각이 그 회차의 좌석 선택 화면 링크다 — 매진·마감은 링크 없이, 누르면 창을 닫는다", async () => {
    // 마감 판정이 실제 시계에 기대지 않게 Date 만 고정한다(타이머는 그대로 — userEvent·react-query)
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-17T19:42:00+09:00"));
    const answer = {
      ...cacheSemantic.value,
      answer: "오늘 19시 이후 회차: 3건입니다.",
      // 잔여석을 물은 답 — 계약 예시(회차 목록)에는 잔여석 열이 없어 여기서 붙인다
      columns: [...cacheSemantic.value.columns, { name: "remaining_seats", type: "integer" }],
      rows: [
        ["영화 제목", "건대입구", "6관", "2026-11-17 20:10", 121],
        ["영화 제목", "건대입구", "2관", "2026-11-17 20:40", 0],
        ["다른 영화", "노원", "1관", "2026-11-17 19:45", 160], // 시작 3분 전 → 마감
      ],
      screeningIds: [769004021, 769004032, 769011011],
    };
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/ai/presets") return json(200, presets);
      if (url.startsWith("/api/movies")) return json(200, { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
      return json(200, answer);
    });
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.type(await screen.findByRole("textbox", { name: "질문" }), "오늘 19시 이후 영화{Enter}");

    const link = await screen.findByRole("link", { name: "11/17 20:10 좌석 고르기" });
    expect(link).toHaveAttribute("href", "/screenings/769004021");
    expect(link).toHaveTextContent("11/17 20:10");
    // 열은 늘지 않는다(영화·극장·상영관·시작·잔여석)
    expect(screen.getAllByRole("columnheader")).toHaveLength(answer.columns.length);
    expect(screen.getAllByRole("link", { name: /좌석 고르기/ })).toHaveLength(1);
    const rows = screen.getAllByRole("row").slice(1);
    expect(within(rows[1]).queryByRole("link")).not.toBeInTheDocument(); // 매진(잔여석 0)
    expect(within(rows[1]).getByText("11/17 20:40")).toBeInTheDocument();
    expect(within(rows[2]).queryByRole("link")).not.toBeInTheDocument();
    expect(within(rows[2]).getByText("마감")).toBeInTheDocument();
    expect(screen.getByText("시각을 누르면 좌석 선택으로 이동해요")).toBeInTheDocument();

    // 좌석 화면으로 넘어가면 대화창을 닫는다 — 좁은 화면에서 창이 좌석 화면을 가리지 않게. 대화는 남는다
    await userEvent.click(link);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "AI 상영 도우미 열기" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "AI 상영 도우미 열기" }));
    expect(await screen.findByText(answer.answer)).toBeInTheDocument();
  });

  it("답에 영화가 여럿이면 5개씩 고르는 버튼, 「더 보기」마다 5개씩 더, 고르면 그 영화 상영 정보를 묻는다", async () => {
    const titles = Array.from({ length: 7 }, (_, i) => `영화${i + 1}`);
    const many = {
      ...cacheSemantic.value,
      path: "NL2SQL",
      answer: "런타임 2시간 이상 영화: 7건입니다. 영화는 영화1, 영화2, 영화3 외 4편이 있습니다.",
      columns: [{ name: "movie_title", type: "text" }],
      rows: titles.map((t) => [t]),
      rowCount: 7,
    };
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/ai/presets") return json(200, presets);
      if (url.startsWith("/api/movies")) {
        return json(200, {
          content: [{ movieId: 6, title: "영화6", titleEn: null, viewGrade: "12", slotMinutes: 130, posterUrl: null }],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        });
      }
      return json(200, many);
    });
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.type(await screen.findByRole("textbox", { name: "질문" }), "런타임 2시간 이상인 영화는 뭐야{Enter}");

    const group = await screen.findByRole("group", { name: "영화 고르기" });
    const names = () => within(group).getAllByRole("button").map((b) => b.textContent);
    expect(names()).toEqual(["영화1", "영화2", "영화3", "영화4", "영화5", "더 보기 (2)"]);
    await userEvent.click(within(group).getByRole("button", { name: "더 보기 (2)" }));
    expect(names()).toEqual(titles);

    await userEvent.click(within(group).getByRole("button", { name: "영화6" }));
    await waitFor(() => {
      const chats = fetchMock.mock.calls.filter(([url]) => String(url) === "/api/ai/chat");
      expect(JSON.parse(String(chats[1]?.[1]?.body)).message).toBe("영화6 어디서 몇 시에 해?");
    });
    // catalog 는 size 1~50 만 받는다(MovieController @Max(50)) — 넘으면 400 이라 링크가 사라진다
    const movieCall = fetchMock.mock.calls.find(([url]) => String(url).startsWith("/api/movies"));
    expect(new URL(String(movieCall?.[0]), "http://x").searchParams.get("size")).toBe("50");
    expect((await screen.findAllByRole("link", { name: /영화6 상영시간표 보기/ }))[0].getAttribute("href")).toBe(
      "/showtimes?movieId=6",
    );
  });
});
