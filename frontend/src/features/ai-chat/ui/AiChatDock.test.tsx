import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SessionContext, unavailableSession, type Session } from "@/entities/session";
import { AiChatDock } from "./AiChatDock";
import nl2sql from "../../../../../contracts/openapi/examples/ai/chat-nl2sql.json";
import cacheSemantic from "../../../../../contracts/openapi/examples/ai/chat-cache-semantic.json";

const TRACE = "0123456789abcdef0123456789abcdef";
const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
const presets = {
  items: [{ presetId: "today_after_time", title: "오늘 ○시 이후 회차", example: "오늘 20시 이후에 시작하는 영화 알려줘" }],
};

const authenticated: Session = {
  ...unavailableSession,
  status: "authenticated",
  user: { id: "00000000-0000-4000-8000-000000000001", name: "한채연", email: null },
};

function renderDock(session: Session = authenticated) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SessionContext.Provider value={session}>
        <AiChatDock />
      </SessionContext.Provider>
    </QueryClientProvider>,
  );
}

describe("AiChatDock (3.3.7)", () => {
  afterEach(() => {
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

  it("추천 질문을 누르면 example 을 보내고 답·결과 표·기준 시각을 보여 준다. 다음 질문엔 sessionId 를 붙인다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url === "/api/ai/presets") return json(200, presets);
      return json(200, fetchMock.mock.calls.length <= 2 ? cacheSemantic.value : nl2sql.value);
    });
    renderDock();
    await userEvent.click(await screen.findByRole("button", { name: "AI 상영 도우미 열기" }));
    await userEvent.click(await screen.findByRole("button", { name: "오늘 ○시 이후 회차" }));

    expect(await screen.findByText(cacheSemantic.value.answer)).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "영화" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "11/17 20:10" })).toBeInTheDocument();
    expect(screen.getByText(/19:42 기준/)).toBeInTheDocument();
    const [, firstInit] = fetchMock.mock.calls[1];
    expect(JSON.parse(String(firstInit?.body))).toEqual({ message: "오늘 20시 이후에 시작하는 영화 알려줘" });

    await userEvent.type(screen.getByRole("textbox", { name: "질문" }), "매진 임박한 회차{Enter}");
    expect(await screen.findByText(nl2sql.value.answer)).toBeInTheDocument();
    const [, secondInit] = fetchMock.mock.calls[2];
    expect(JSON.parse(String(secondInit?.body))).toEqual({
      message: "매진 임박한 회차",
      sessionId: cacheSemantic.value.sessionId,
    });
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
});
