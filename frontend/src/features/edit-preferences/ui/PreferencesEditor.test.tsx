import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Theater } from "@/entities/theater";
import type { Preferences } from "@/entities/user";
import { PreferencesEditor } from "./PreferencesEditor";

const theaters: Theater[] = [
  { theaterId: 4, name: "건대입구", region: "서울", hasLayout: true },
  { theaterId: 16, name: "영등포", region: "서울", hasLayout: true },
  { theaterId: 18, name: "월드타워", region: "서울", hasLayout: true },
  { theaterId: 118, name: "부산본점", region: "부산", hasLayout: true },
];

const initial: Preferences = {
  preferredTheaters: [
    { rank: 1, theaterId: 4 },
    { rank: 2, theaterId: 18 },
  ],
  preferredTimeSlot: "EVENING",
  preferredGenres: ["드라마"],
};

const jsonResponse = (body: unknown) =>
  new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

function renderEditor() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PreferencesEditor theaters={theaters} initial={initial} />
    </QueryClientProvider>,
  );
}

describe("PreferencesEditor (W8 선호 설정)", () => {
  afterEach(() => vi.restoreAllMocks());

  it("저장된 값을 보여 주고, 바뀐 게 없으면 저장 버튼이 비활성", () => {
    renderEditor();
    const theaterGroup = screen.getByRole("group", { name: "선호 극장 선택" });
    expect(within(theaterGroup).getByRole("button", { name: "건대입구" })).toHaveAttribute("aria-pressed", "true");
    expect(within(theaterGroup).getByRole("button", { name: "영등포" })).toHaveAttribute("aria-pressed", "false");
    const ranks = within(screen.getByRole("list", { name: "선호 극장 순위" })).getAllByRole("listitem");
    expect(ranks.map((li) => li.textContent)).toEqual(["01건대입구", "02월드타워"]);
    expect(screen.getByRole("button", { name: "저녁 17~21시" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "선호 설정 저장" })).toBeDisabled();
  });

  it("극장은 3곳까지, 순위 버튼으로 순서를 바꾸고, 시간대는 하나만 → PUT 본문에 rank 1~N", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (_url, init) => {
      return jsonResponse(JSON.parse(String(init?.body)));
    });
    renderEditor();

    await userEvent.click(screen.getByRole("button", { name: "영등포" }));
    await userEvent.click(screen.getByRole("button", { name: "부산본점" })); // 4번째 → 무시 + 안내
    const list = screen.getByRole("list", { name: "선호 극장 순위" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(3);
    expect(screen.getByRole("button", { name: "부산본점" })).toHaveAttribute("aria-pressed", "false");

    await userEvent.click(within(list).getByRole("button", { name: "영등포 순위 올리기" }));
    expect(within(list).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "01건대입구",
      "02영등포",
      "03월드타워",
    ]);

    await userEvent.click(screen.getByRole("button", { name: "심야 21시~" }));
    expect(screen.getByRole("button", { name: "저녁 17~21시" })).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(screen.getByRole("button", { name: "액션" }));

    const save = screen.getByRole("button", { name: "선호 설정 저장" });
    expect(save).toBeEnabled();
    await userEvent.click(save);

    expect(await screen.findByText("저장된 상태예요")).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/users/me/preferences");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(String(init.body))).toEqual({
      preferredTheaters: [
        { rank: 1, theaterId: 4 },
        { rank: 2, theaterId: 16 },
        { rank: 3, theaterId: 18 },
      ],
      preferredTimeSlot: "NIGHT",
      preferredGenres: ["드라마", "액션"],
    });
    expect(screen.getByRole("button", { name: "선호 설정 저장" })).toBeDisabled();
  });
});
