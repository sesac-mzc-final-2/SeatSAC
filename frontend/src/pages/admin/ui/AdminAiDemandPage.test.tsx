import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AdminAiDemandPage } from "./AdminAiDemandPage";
import { errorBody, jsonResponse, renderPanel } from "./admin-test-utils";

const STATS = {
  from: "2026-11-19",
  to: "2026-11-25",
  total: 42,
  byCategory: [
    { category: "site_feature", count: 9 },
    { category: "recommendation", count: 19 },
    { category: "action_request", count: 14 },
    { category: "new_kind", count: 0 },
  ],
  top: [
    { category: "recommendation", reason: "조건 없이 볼 만한 영화를 추천해 달라는 요청", count: 11 },
    { category: "action_request", reason: "예매 취소를 채팅으로 해 달라는 요청", count: 8 },
  ],
};

describe("관리자 AI 범위 밖 질문 (6.3.7, OPS-23)", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-11-25T10:00:00+09:00"));
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("최근 7일 통계: 분류별 막대는 많은 순·한국어 이름·비율, 모르는 분류는 글자 그대로", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, STATS));
    renderPanel(<AdminAiDemandPage />);

    const bars = await screen.findByRole("list", { name: "분류별 질문 수" });
    const rows = within(bars).getAllByRole("listitem");
    expect(rows.map((row) => row.textContent)).toEqual([
      "영화 추천19건 (45%)",
      "기능 실행 요청14건 (33%)",
      "사이트 이용 문의9건 (21%)",
      "new_kind0건 (0%)",
    ]);
    expect(screen.getByText("42건")).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0]![0])).toBe("/ops/ai/unmet-demand?from=2026-11-19&to=2026-11-25&limit=10");

    const top = screen.getByRole("list", { name: "많이 나온 요청" });
    expect(within(top).getAllByRole("listitem")[0]).toHaveTextContent("영화 추천조건 없이 볼 만한 영화를 추천해 달라는 요청11건");
  });

  it("기간을 30일로 바꾸면 from 이 29일 전으로", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(200, STATS));
    renderPanel(<AdminAiDemandPage />);
    await screen.findByRole("list", { name: "분류별 질문 수" });

    await userEvent.click(screen.getByRole("tab", { name: "최근 30일" }));
    await screen.findByText("10월 27일 (화) ~ 11월 25일 (수)");
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain(
      "/ops/ai/unmet-demand?from=2026-10-27&to=2026-11-25&limit=10",
    );
  });

  it("0건이면 빈 안내", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse(200, { ...STATS, total: 0, byCategory: [], top: [] }),
    );
    renderPanel(<AdminAiDemandPage />);
    expect(await screen.findByRole("heading", { name: "이 기간에는 범위 밖 질문이 없어요" })).toBeInTheDocument();
  });

  it("503(ai 를 부르지 못함)이면 「통계 없음」, 403 이면 권한 안내", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(503, errorBody("SERVICE_UNAVAILABLE")));
    const { unmount } = renderPanel(<AdminAiDemandPage />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("통계 없음");
    expect(alert).toHaveTextContent("AI 서버(ai)에서 통계를 받지 못했어요");
    unmount();

    vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse(403, errorBody("FORBIDDEN")));
    renderPanel(<AdminAiDemandPage />);
    expect(await screen.findByRole("heading", { name: "관리자 권한이 필요해요" })).toBeInTheDocument();
  });
});
