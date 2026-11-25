import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CouponSelect } from "./CouponSelect";

const jsonResponse = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

/** 「결제 예정 금액」 줄의 값 */
const finalAmount = () => screen.getByText("결제 예정 금액").nextElementSibling?.textContent;

const future = new Date(Date.now() + 3600_000).toISOString();
const past = new Date(Date.now() - 1000).toISOString();

function Harness({ onPick, initial = null }: { onPick: (id: string | null) => void; initial?: string | null }) {
  const [value, setValue] = useState<string | null>(initial);
  return (
    <CouponSelect
      amount={45000}
      value={value}
      onChange={(id) => {
        setValue(id);
        onPick(id);
      }}
    />
  );
}

function renderSelect(onPick = vi.fn(), initial: string | null = null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <Harness onPick={onPick} initial={initial} />
    </QueryClientProvider>,
  );
  return onPick;
}

describe("CouponSelect (6.3.6)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("쓸 수 있는 쿠폰만 보이고, 고르면 couponIssueId 와 예상 결제 금액", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      jsonResponse({
        items: [
          { couponIssueId: "a", eventId: 3, title: "VIP 50%", discountRate: 50, state: "ISSUED", expiresAt: future },
          { couponIssueId: "b", eventId: 2, title: "지난 쿠폰", discountRate: 30, state: "ISSUED", expiresAt: past },
          { couponIssueId: "c", eventId: 1, title: "쓴 쿠폰", discountRate: 10, state: "USED", expiresAt: future },
        ],
      }),
    );
    const onPick = renderSelect();

    const option = await screen.findByRole("radio", { name: /VIP 50%/ });
    expect(screen.queryByText("지난 쿠폰")).not.toBeInTheDocument();
    expect(screen.queryByText("쓴 쿠폰")).not.toBeInTheDocument();
    expect(finalAmount()).toBe("45,000원");

    await userEvent.click(option);
    expect(onPick).toHaveBeenLastCalledWith("a");
    expect(finalAmount()).toBe("22,500원");
    expect(screen.getByText("−22,500원", { selector: "dd" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("radio", { name: "쿠폰 사용 안 함" }));
    expect(onPick).toHaveBeenLastCalledWith(null);
  });

  it("쓸 쿠폰이 없으면 그리지 않는다", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(jsonResponse({ items: [] }));
    renderSelect();
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(screen.queryByRole("group")).not.toBeInTheDocument();
  });

  it("내 쿠폰을 끝내 못 읽으면 고른 쿠폰을 푼다 (화면에 없는 쿠폰이 결제에 실려 가지 않게)", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ code: "SERVICE_UNAVAILABLE", message: "", traceId: "0".repeat(32), details: [] }), {
        status: 503,
        headers: { "Content-Type": "application/json" },
      }),
    );
    const onPick = renderSelect(vi.fn(), "a");
    await vi.waitFor(() => expect(onPick).toHaveBeenCalledWith(null));
  });
});
