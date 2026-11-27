import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ToastProvider } from "@/shared/ui";
import { AdminCouponsPage } from "./AdminCouponsPage";
import { errorBody, jsonResponse, renderPanel } from "./admin-test-utils";

const EVENT = {
  eventId: 3,
  title: "VIP 선착순 50% 할인",
  discountRate: 50,
  startsAt: "2026-11-27T14:00:00+09:00",
  endsAt: "2026-11-27T15:00:00+09:00",
  totalQuantity: 100,
  issuedQuantity: 64,
  remainingQuantity: 36,
  eligibleGrade: "VIP",
  state: "OPEN",
  createdAt: "2026-11-27T13:40:00+09:00",
};

/** 메서드·경로로 응답을 고르는 fetch 목 */
function mockOps(routes: Record<string, (init?: RequestInit) => Response>) {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const key = `${init?.method ?? "GET"} ${String(input)}`;
    const handler = routes[key];
    if (!handler) throw new Error(`unexpected ${key}`);
    return handler(init);
  });
}

const render = () =>
  renderPanel(
    <ToastProvider>
      <AdminCouponsPage />
    </ToastProvider>,
  );

const form = () => screen.getByRole("form", { name: "쿠폰 이벤트 만들기" });

describe("관리자 쿠폰 이벤트 (6.3.9, OPS-24)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("최근 이벤트 목록: 상태·할인·기간·발급/수량·남은 수량", async () => {
    mockOps({ "GET /ops/coupons/events": () => jsonResponse(200, { items: [EVENT] }) });
    render();

    const row = (await screen.findByRole("rowheader", { name: /VIP 선착순 50% 할인/ })).closest("tr")!;
    expect(within(row).getByText("진행 중")).toBeInTheDocument();
    expect(within(row).getByText("50%")).toBeInTheDocument();
    expect(within(row).getByText("11월 27일 (금) 14:00 ~ 15:00")).toBeInTheDocument();
    expect(within(row).getByText("64/100")).toBeInTheDocument();
    expect(within(row).getByText("36")).toBeInTheDocument();
  });

  it("이벤트가 없으면 빈 안내 + 만들기 폼", async () => {
    mockOps({ "GET /ops/coupons/events": () => jsonResponse(200, { items: [] }) });
    render();
    expect(await screen.findByRole("heading", { name: "아직 이벤트가 없어요" })).toBeInTheDocument();
    expect(form()).toBeInTheDocument();
  });

  it("만들기 → POST /ops/coupons/events(시작은 +09:00, 대상 VIP) → 목록 다시 받기", async () => {
    let created = false;
    const fetchMock = mockOps({
      "GET /ops/coupons/events": () => jsonResponse(200, { items: created ? [EVENT] : [] }),
      "POST /ops/coupons/events": () => {
        created = true;
        return jsonResponse(201, { ...EVENT, state: "UPCOMING", issuedQuantity: 0, remainingQuantity: 100 });
      },
    });
    render();
    await screen.findByRole("heading", { name: "아직 이벤트가 없어요" });

    const f = form();
    fireEvent.change(within(f).getByLabelText("시작 (KST)"), { target: { value: "2026-11-27T14:00" } });
    await userEvent.clear(within(f).getByLabelText("수량(선착순)"));
    await userEvent.type(within(f).getByLabelText("수량(선착순)"), "100");
    await userEvent.click(within(f).getByRole("button", { name: /이벤트 만들기/ }));

    expect(await screen.findByText(/이벤트를 만들었어요/)).toBeInTheDocument();
    await screen.findByRole("rowheader", { name: /VIP 선착순 50% 할인/ });
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === "POST")!;
    expect(JSON.parse(String(post[1]!.body))).toEqual({
      title: "VIP 선착순 50% 할인",
      discountRate: 50,
      startsAt: "2026-11-27T14:00:00+09:00",
      durationMinutes: 60,
      totalQuantity: 100,
      eligibleGrade: "VIP",
    });
  });

  it("보내기 전 범위 검사 — 할인율 100이면 요청하지 않는다", async () => {
    const fetchMock = mockOps({ "GET /ops/coupons/events": () => jsonResponse(200, { items: [] }) });
    render();
    await screen.findByRole("heading", { name: "아직 이벤트가 없어요" });

    const f = form();
    await userEvent.clear(within(f).getByLabelText("할인율(%)"));
    await userEvent.type(within(f).getByLabelText("할인율(%)"), "100");
    await userEvent.click(within(f).getByRole("button", { name: /이벤트 만들기/ }));

    expect(within(f).getByRole("alert")).toHaveTextContent("할인율은 1~99%");
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === "POST")).toBe(false);
  });

  it("400 EVENT_OVERLAP · STARTS_IN_PAST는 사유별 문구", async () => {
    let reason = "EVENT_OVERLAP";
    mockOps({
      "GET /ops/coupons/events": () => jsonResponse(200, { items: [EVENT] }),
      "POST /ops/coupons/events": () =>
        jsonResponse(400, { ...errorBody("VALIDATION_FAILED"), details: [{ field: "startsAt", reason }] }),
    });
    render();
    await screen.findByRole("rowheader", { name: /VIP 선착순 50% 할인/ });

    const submit = within(form()).getByRole("button", { name: /이벤트 만들기/ });
    await userEvent.click(submit);
    expect(await within(form()).findByRole("alert")).toHaveTextContent("다른 이벤트와 기간이 겹쳐요");

    reason = "STARTS_IN_PAST";
    await userEvent.click(submit);
    await waitFor(() => expect(within(form()).getByRole("alert")).toHaveTextContent("시작 시각이 이미 지났어요"));
  });

  it("503이면 만들어졌는지 불확실 — 목록으로 확인하라고 안내", async () => {
    mockOps({
      "GET /ops/coupons/events": () => jsonResponse(200, { items: [] }),
      "POST /ops/coupons/events": () => jsonResponse(503, errorBody("SERVICE_UNAVAILABLE")),
    });
    render();
    await screen.findByRole("heading", { name: "아직 이벤트가 없어요" });
    await userEvent.click(within(form()).getByRole("button", { name: /이벤트 만들기/ }));
    expect(await within(form()).findByRole("alert")).toHaveTextContent("만들어졌는지 확실하지 않아요");
  });

  it.each([
    ["CloudFront 502", () => jsonResponse(502, errorBody("INTERNAL_ERROR"))],
    ["CloudFront 504(본문 없음)", () => new Response("<html>504</html>", { status: 504 })],
    [
      "응답 전에 끊긴 네트워크 오류",
      () => {
        throw new TypeError("Failed to fetch");
      },
    ],
  ])("%s도 만들어졌는지 불확실 — 목록으로 확인하라고 안내", async (_, postResponse) => {
    mockOps({
      "GET /ops/coupons/events": () => jsonResponse(200, { items: [] }),
      "POST /ops/coupons/events": postResponse,
    });
    render();
    await screen.findByRole("heading", { name: "아직 이벤트가 없어요" });
    await userEvent.click(within(form()).getByRole("button", { name: /이벤트 만들기/ }));
    expect(await within(form()).findByRole("alert")).toHaveTextContent("만들어졌는지 확실하지 않아요");
  });

  it("「지금 시작」이면 보낼 때 다음 정분으로 다시 계산 — 폼을 오래 열어 둬도 지난 시각을 보내지 않는다", async () => {
    let now = Date.parse("2026-11-27T04:59:30Z"); // KST 13:59:30 — 폼을 연 때
    vi.spyOn(Date, "now").mockImplementation(() => now);
    const fetchMock = mockOps({
      "GET /ops/coupons/events": () => jsonResponse(200, { items: [] }),
      "POST /ops/coupons/events": () => jsonResponse(201, { ...EVENT, state: "UPCOMING" }),
    });
    render();
    await screen.findByRole("heading", { name: "아직 이벤트가 없어요" });
    expect(within(form()).getByLabelText("시작 (KST)")).toHaveValue("2026-11-27T14:00");

    const f = form();
    // 시각을 직접 골랐다가 「지금 시작」으로 되돌린다
    fireEvent.change(within(f).getByLabelText("시작 (KST)"), { target: { value: "2026-11-28T09:00" } });
    await userEvent.click(within(f).getByRole("button", { name: /지금 시작/ }));

    now = Date.parse("2026-11-27T05:12:10Z"); // 12분 뒤 KST 14:12:10에 보낸다
    await userEvent.click(within(f).getByRole("button", { name: /이벤트 만들기/ }));

    await screen.findByText(/이벤트를 만들었어요/);
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === "POST")!;
    expect(JSON.parse(String(post[1]!.body)).startsAt).toBe("2026-11-27T14:13:00+09:00");
  });

  it("목록을 다시 받다 실패해도 마지막 목록은 그대로 두고 안내", async () => {
    let calls = 0;
    mockOps({
      "GET /ops/coupons/events": () =>
        ++calls === 1 ? jsonResponse(200, { items: [EVENT] }) : jsonResponse(503, errorBody("SERVICE_UNAVAILABLE")),
      "POST /ops/coupons/events": () =>
        jsonResponse(400, { ...errorBody("VALIDATION_FAILED"), details: [{ field: "startsAt", reason: "EVENT_OVERLAP" }] }),
    });
    render();
    await screen.findByRole("rowheader", { name: /VIP 선착순 50% 할인/ });

    // 만들기가 끝나면(실패여도) 목록을 다시 받는다 → 그 재조회가 503
    await userEvent.click(within(form()).getByRole("button", { name: /이벤트 만들기/ }));
    expect(await screen.findByText(/목록을 새로 받지 못했어요/)).toBeInTheDocument();
    expect(screen.getByRole("rowheader", { name: /VIP 선착순 50% 할인/ })).toBeInTheDocument();
  });

  it("ADMIN 그룹이 아니면(403) 권한 안내", async () => {
    mockOps({ "GET /ops/coupons/events": () => jsonResponse(403, errorBody("FORBIDDEN")) });
    render();
    expect(await screen.findByRole("heading", { name: "관리자 권한이 필요해요" })).toBeInTheDocument();
  });
});
