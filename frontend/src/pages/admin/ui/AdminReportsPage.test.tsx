import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminReportsPage } from "./AdminReportsPage";
import { errorBody, jsonResponse, renderPanel } from "./admin-test-utils";

const DATES = { items: ["2026-11-24", "2026-11-23"] };
const READY = {
  downloadUrl: "https://reports.example.invalid/errors/2026-11-24.csv?signature=example",
  rowCount: 128,
  generatedAt: "2026-11-24T14:02:11+09:00",
};

/** URL 경로·쿼리로 응답을 고르는 fetch 목 */
function mockOps(routes: Record<string, () => Response>) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = String(input);
    const key = Object.keys(routes).find((route) => url === route || url.startsWith(`${route}?`));
    if (!key) throw new Error(`unexpected ${url}`);
    return routes[key]!();
  });
}

describe("관리자 오류 리포트 (6.3.7, OPS-22)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("최근 날짜가 먼저 골라져 있고, 캐시된 결과(200)면 바로 CSV 내려받기 링크", async () => {
    const fetchMock = mockOps({
      "/ops/reports/errors/dates": () => jsonResponse(200, DATES),
      "/ops/reports/errors": () => jsonResponse(200, READY),
    });
    renderPanel(<AdminReportsPage />);

    const select = await screen.findByRole("combobox", { name: "리포트 날짜" });
    expect(select).toHaveValue("2026-11-24");
    expect(screen.getByRole("option", { name: "2026년 11월 23일 (월)" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "리포트 만들기" }));
    const link = await screen.findByRole("link", { name: "CSV 내려받기" });
    expect(link).toHaveAttribute("href", READY.downloadUrl);
    expect(screen.getByText(/128행/)).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[1]![0])).toBe("/ops/reports/errors?date=2026-11-24");
  });

  it("쿼리를 시작하면(202) 상태를 보고 SUCCEEDED 면 내려받기, 다른 날짜를 고르면 처음으로", async () => {
    const fetchMock = mockOps({
      "/ops/reports/errors/dates": () => jsonResponse(200, DATES),
      "/ops/reports/errors": () => jsonResponse(202, { queryId: "q-1", state: "RUNNING" }),
      "/ops/reports/errors/q-1": () => jsonResponse(200, { state: "SUCCEEDED", ...READY, rowCount: 0 }),
    });
    renderPanel(<AdminReportsPage />);

    await userEvent.click(await screen.findByRole("button", { name: "리포트 만들기" }));
    expect(await screen.findByRole("link", { name: "CSV 내려받기" })).toHaveAttribute("href", READY.downloadUrl);
    expect(screen.getByText(/기록된 오류가 없어요/)).toBeInTheDocument();
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain("/ops/reports/errors/q-1");

    await userEvent.selectOptions(screen.getByRole("combobox", { name: "리포트 날짜" }), "2026-11-23");
    expect(screen.queryByRole("link", { name: "CSV 내려받기" })).not.toBeInTheDocument();
  });

  it("날짜를 A→B→A 로 바꾸면 A 의 이전 결과를 다시 보이지 않고 처음 화면", async () => {
    mockOps({
      "/ops/reports/errors/dates": () => jsonResponse(200, DATES),
      "/ops/reports/errors": () => jsonResponse(200, READY),
    });
    renderPanel(<AdminReportsPage />);

    await userEvent.click(await screen.findByRole("button", { name: "리포트 만들기" }));
    expect(await screen.findByRole("link", { name: "CSV 내려받기" })).toBeInTheDocument();

    const select = screen.getByRole("combobox", { name: "리포트 날짜" });
    await userEvent.selectOptions(select, "2026-11-23");
    await userEvent.selectOptions(select, "2026-11-24");
    expect(screen.queryByRole("link", { name: "CSV 내려받기" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "리포트 만들기" })).toBeEnabled();
  });

  it("A 요청 중 B→A 로 바꾼 뒤 늦게 온 첫 A 응답은 버린다 (날짜가 같아도 세대가 다름)", async () => {
    let resolveLate: (response: Response) => void = () => {};
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      if (String(input) === "/ops/reports/errors/dates") return jsonResponse(200, DATES);
      return new Promise<Response>((resolve) => {
        resolveLate = resolve;
      });
    });
    renderPanel(<AdminReportsPage />);

    await userEvent.click(await screen.findByRole("button", { name: "리포트 만들기" }));
    const select = screen.getByRole("combobox", { name: "리포트 날짜" });
    await userEvent.selectOptions(select, "2026-11-23");
    await userEvent.selectOptions(select, "2026-11-24");
    resolveLate(jsonResponse(200, READY));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByRole("link", { name: "CSV 내려받기" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "리포트 만들기" })).toBeEnabled();
  });

  it("쿼리가 FAILED 면 실패 안내와 다시 만들기", async () => {
    mockOps({
      "/ops/reports/errors/dates": () => jsonResponse(200, DATES),
      "/ops/reports/errors": () => jsonResponse(202, { queryId: "q-2", state: "RUNNING" }),
      "/ops/reports/errors/q-2": () => jsonResponse(200, { state: "FAILED" }),
    });
    renderPanel(<AdminReportsPage />);

    await userEvent.click(await screen.findByRole("button", { name: "리포트 만들기" }));
    expect(await screen.findByText(/리포트 쿼리가 실패했어요/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "다시 만들기" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "리포트 만들기" })).toBeEnabled());
  });

  it("날짜가 없으면 빈 안내, 503 REPORT_UNAVAILABLE 이면 저장소 오류 문구", async () => {
    mockOps({ "/ops/reports/errors/dates": () => jsonResponse(200, { items: [] }) });
    const { unmount } = renderPanel(<AdminReportsPage />);
    expect(await screen.findByRole("heading", { name: "아직 리포트가 없어요" })).toBeInTheDocument();
    unmount();
    vi.restoreAllMocks();

    mockOps({ "/ops/reports/errors/dates": () => jsonResponse(503, errorBody("REPORT_UNAVAILABLE")) });
    renderPanel(<AdminReportsPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("리포트 저장소(Athena·S3)를 읽지 못했어요");
  });

  it("ops-api 가 403 이면 권한 안내", async () => {
    mockOps({ "/ops/reports/errors/dates": () => jsonResponse(403, errorBody("FORBIDDEN")) });
    renderPanel(<AdminReportsPage />);
    expect(await screen.findByRole("heading", { name: "관리자 권한이 필요해요" })).toBeInTheDocument();
  });
});
