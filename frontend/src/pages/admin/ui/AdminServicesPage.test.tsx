import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/shared/ui";
import { AdminServicesPage } from "./AdminServicesPage";
import { errorBody, jsonResponse, renderPanel } from "./admin-test-utils";

const status = (overrides: Record<string, unknown> = {}) => ({
  items: [
    { service: "gateway", kind: "ROLLOUT", desired: 2, ready: 2, state: 0, hpa: null, stopped: false },
    {
      service: "user",
      kind: "DEPLOYMENT",
      desired: 2,
      ready: 2,
      state: 1,
      hpa: { min: 2, max: 2, current: 2, cpuPercent: 12.4 },
      stopped: false,
    },
    { service: "coupon", kind: "DEPLOYMENT", desired: 0, ready: 0, state: null, hpa: null, stopped: true },
    { service: "ops-api", kind: "DEPLOYMENT", desired: 1, ready: 1, state: 2, hpa: null, stopped: false },
  ],
  nodes: [
    { name: "ip-10-0-1-1", zone: "ap-northeast-2a", cpuPercent: 31.6, memoryPercent: null, capacityType: "ON_DEMAND" },
  ],
  asOf: "2026-11-27T14:00:05+09:00",
  ...overrides,
});

/** 메서드·경로로 응답을 고르는 fetch 목 */
function mockOps(routes: Record<string, () => Response>) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const key = `${init?.method ?? "GET"} ${String(input)}`;
    const handler = routes[key];
    if (!handler) throw new Error(`unexpected ${key}`);
    return handler();
  });
}

const render = () =>
  renderPanel(
    <ToastProvider>
      <AdminServicesPage />
    </ToastProvider>,
  );

const rowOf = (service: string) => screen.getByRole("rowheader", { name: new RegExp(`^${service}`) }).closest("tr")!;

describe("관리자 서비스 상태 (6.2.6, OPS-20·21)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("서비스별 신호등·파드·HPA와 노드 사용률을 보여 주고, 정지된 서비스를 알린다", async () => {
    mockOps({ "GET /ops/services": () => jsonResponse(200, status()) });
    render();

    expect(await screen.findByText("14:00 기준 · 5초마다 갱신")).toBeInTheDocument();
    expect(within(rowOf("gateway")).getByText("정상")).toBeInTheDocument();
    expect(within(rowOf("gateway")).getByText("블루/그린")).toBeInTheDocument();
    expect(within(rowOf("user")).getByText("주의")).toBeInTheDocument();
    expect(within(rowOf("user")).getByText("2~2 · CPU 12%")).toBeInTheDocument();
    expect(within(rowOf("coupon")).getByText("트래픽 없음")).toBeInTheDocument();
    expect(within(rowOf("coupon")).getByText("0/0")).toBeInTheDocument();
    expect(within(rowOf("coupon")).getByText("정지됨")).toBeInTheDocument();
    expect(screen.getByText(/^정지 중:/).closest("p")).toHaveTextContent("정지 중: coupon");
    expect(screen.getByText("ip-10-0-1-1")).toBeInTheDocument();
    expect(screen.getByText("32%")).toBeInTheDocument();
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("정지 버튼 → 확인 → POST /ops/services/{svc}/stop, 끝나면 상태를 다시 받는다 · ops-api는 버튼 없음", async () => {
    let stopped = false;
    const fetchMock = mockOps({
      "GET /ops/services": () =>
        jsonResponse(
          200,
          stopped
            ? status({
                items: status().items.map((item) =>
                  item.service === "user" ? { ...item, desired: 0, ready: 0, stopped: true } : item,
                ),
              })
            : status(),
        ),
      "POST /ops/services/user/stop": () => {
        stopped = true;
        return jsonResponse(202, { service: "user", desired: 0 });
      },
    });
    render();

    await screen.findByText("14:00 기준 · 5초마다 갱신");
    expect(within(rowOf("ops-api")).queryByRole("button")).not.toBeInTheDocument();
    expect(within(rowOf("ops-api")).getByText("대상 아님")).toBeInTheDocument();

    await userEvent.click(within(rowOf("user")).getByRole("button", { name: "user 정지" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveTextContent("user 서비스를 정지할까요?");
    await userEvent.click(within(dialog).getByRole("button", { name: "정지하기" }));

    await waitFor(() => expect(within(rowOf("user")).getByRole("button", { name: "user 가동" })).toBeInTheDocument());
    expect(await screen.findByText(/user 서비스를 정지했어요/)).toBeInTheDocument();
    const stopCall = fetchMock.mock.calls.find(([, init]) => init?.method === "POST")!;
    expect(String(stopCall[0])).toBe("/ops/services/user/stop");
  });

  it("가동 버튼은 /start를 부른다", async () => {
    const fetchMock = mockOps({
      "GET /ops/services": () => jsonResponse(200, status()),
      "POST /ops/services/coupon/start": () => jsonResponse(202, { service: "coupon", desired: 2 }),
    });
    render();

    await userEvent.click(within(await waitFor(() => rowOf("coupon"))).getByRole("button", { name: "coupon 가동" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "가동하기" }));
    expect(await screen.findByText(/coupon 서비스를 가동했어요\(파드 2개\)/)).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url, init]) => url === "/ops/services/coupon/start" && init?.method === "POST")).toBe(true);
  });

  it("블루/그린 중(409 ROLLOUT_IN_PROGRESS)이면 안내 문구", async () => {
    mockOps({
      "GET /ops/services": () => jsonResponse(200, status()),
      "POST /ops/services/gateway/stop": () => jsonResponse(409, errorBody("ROLLOUT_IN_PROGRESS")),
    });
    render();

    await userEvent.click(within(await waitFor(() => rowOf("gateway"))).getByRole("button", { name: "gateway 정지" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "정지하기" }));
    expect(await screen.findByText(/블루\/그린 배포가 진행 중이에요/)).toBeInTheDocument();
  });

  it("다시 받다 실패하면 마지막 표를 그대로 두고 알린다 (ops-api 파드가 옮겨 가는 동안)", async () => {
    let calls = 0;
    const fetchMock = mockOps({
      "GET /ops/services": () => (++calls === 1 ? jsonResponse(200, status()) : jsonResponse(503, errorBody("SERVICE_UNAVAILABLE"))),
      "POST /ops/services/user/stop": () => jsonResponse(202, { service: "user", desired: 0 }),
    });
    render();

    await userEvent.click(within(await waitFor(() => rowOf("user"))).getByRole("button", { name: "user 정지" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "정지하기" }));

    expect(await screen.findByText(/서비스 상태를 새로 받지 못했어요 — 14:00 기준 마지막 상태예요/)).toBeInTheDocument();
    expect(rowOf("gateway")).toBeInTheDocument();
    expect(screen.queryByText("서비스 상태를 불러오지 못했어요")).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => url === "/ops/services").length).toBeGreaterThanOrEqual(2);
  });

  it("처음부터 못 받으면(503) 오류 화면", async () => {
    mockOps({ "GET /ops/services": () => jsonResponse(503, errorBody("SERVICE_UNAVAILABLE")) });
    render();
    expect(await screen.findByText("서비스 상태를 불러오지 못했어요")).toBeInTheDocument();
  });

  it("ADMIN 그룹이 아니면(403) 권한 안내", async () => {
    mockOps({ "GET /ops/services": () => jsonResponse(403, errorBody("FORBIDDEN")) });
    render();
    expect(await screen.findByRole("heading", { name: "관리자 권한이 필요해요" })).toBeInTheDocument();
  });
});
