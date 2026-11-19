import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ResultTable } from "./ResultTable";

describe("ResultTable", () => {
  afterEach(() => vi.restoreAllMocks());

  it("같은 열 이름이 두 번 와도 key 경고 없이 열마다 값을 그린다", () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    render(
      <ResultTable
        caption="결과 1건"
        columns={[
          { name: "name", type: "text" },
          { name: "name", type: "text" },
        ]}
        rows={[["건대입구", "6관"]]}
      />,
    );
    expect(screen.getAllByRole("columnheader", { name: "이름" })).toHaveLength(2);
    expect(screen.getByRole("cell", { name: "건대입구" })).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "6관" })).toBeInTheDocument();
    const keyWarnings = consoleError.mock.calls.filter((args) => args.some((a) => String(a).includes("same key")));
    expect(keyWarnings).toEqual([]);
  });

  it("긴 글자 열 하나만 줄바꿈하고 나머지는 한 줄로 둔다", () => {
    render(
      <ResultTable
        caption="결과 1건"
        columns={[
          { name: "theater_name", type: "text" },
          { name: "movie_title", type: "text" },
          { name: "starts_at", type: "text" },
          { name: "remaining_seats", type: "integer" },
        ]}
        rows={[["건대입구", "극장판 치이카와: 인어 섬의 비밀", "2026-11-18 20:20", 129]]}
      />,
    );
    expect(screen.getByRole("cell", { name: "극장판 치이카와: 인어 섬의 비밀" })).toHaveAttribute("data-wrap", "true");
    expect(screen.getByRole("cell", { name: "건대입구" })).not.toHaveAttribute("data-wrap");
    expect(screen.getByRole("cell", { name: "11/18 20:20" })).not.toHaveAttribute("data-wrap");
    expect(screen.getByRole("columnheader", { name: "영화" })).toHaveAttribute("data-wrap", "true");
  });

  it("긴 글자 열이 없으면 첫 글자 열을 줄바꿈한다", () => {
    render(
      <ResultTable
        caption="결과 1건"
        columns={[
          { name: "screening_count", type: "integer" },
          { name: "theater_name", type: "text" },
        ]}
        rows={[[12, "건대입구"]]}
      />,
    );
    expect(screen.getByRole("cell", { name: "건대입구" })).toHaveAttribute("data-wrap", "true");
  });

  const HINT = "시각을 누르면 좌석 선택으로 이동해요";
  const screeningColumns = [
    { name: "movie_title", type: "text" as const },
    { name: "starts_at", type: "text" as const },
    { name: "remaining_seats", type: "integer" as const },
  ];

  it("회차 한 줄인 답은 열을 더 두지 않고 시작 시각 글자가 좌석 선택 화면 링크다 — 매진·마감은 링크 없이", async () => {
    const onSeatNavigate = vi.fn();
    render(
      <MemoryRouter>
        <ResultTable
          caption="결과 4건"
          columns={screeningColumns}
          rows={[
            ["별의 항해", "2026-11-18 20:20", 129],
            ["한강의 밤", "2026-11-18 21:00", 0],
            ["고양이 탐정", "2026-11-18 19:42", 80],
            ["철의 심장", "2026-11-18 22:30", 40],
          ]}
          seatLinks={[
            { kind: "link", href: "/screenings/769004021" },
            { kind: "blocked", text: "매진" },
            { kind: "blocked", text: "마감" },
            null,
          ]}
          onSeatNavigate={onSeatNavigate}
        />
      </MemoryRouter>,
    );
    // 열은 그대로 3개
    expect(screen.getAllByRole("columnheader").map((th) => th.textContent)).toEqual(["영화", "시작", "잔여석"]);
    const rows = screen.getAllByRole("row").slice(1);
    for (const row of rows) expect(within(row).getAllByRole("cell")).toHaveLength(3);

    // 보이는 글자는 시각 그대로, 이름은 보이는 글자로 시작한다
    const link = screen.getByRole("link", { name: "11/18 20:20 좌석 고르기" });
    expect(link).toHaveAttribute("href", "/screenings/769004021");
    expect(link).toHaveTextContent(/^11\/18 20:20$/);
    expect(screen.getAllByRole("link")).toHaveLength(1);

    // 매진: 시각은 글자만, 「매진」은 화면을 읽는 사람에게만(잔여석 0 이 이미 보인다)
    const soldOut = within(rows[1]).getAllByRole("cell")[1];
    expect(soldOut).toHaveTextContent("11/18 21:00 매진");
    expect(within(soldOut).getByText("매진")).toHaveClass("sr-only");
    // 마감: 시각 옆 작은 글자
    const closed = within(rows[2]).getAllByRole("cell")[1];
    expect(closed).toHaveTextContent("11/18 19:42마감");
    expect(within(closed).getByText("마감")).not.toHaveClass("sr-only");
    // 회차를 모르는 행은 시각 글자만
    expect(within(rows[3]).getAllByRole("cell")[1]).toHaveTextContent(/^11\/18 22:30$/);

    expect(screen.getByText(HINT)).toBeInTheDocument();
    // 링크를 누르면 알린다(대화창 닫기)
    await userEvent.click(link);
    expect(onSeatNavigate).toHaveBeenCalledTimes(1);
  });

  it("Ctrl·Cmd·Shift+클릭(새 탭·새 창)은 이 화면이 그대로라 알리지 않는다 — 보통 클릭만 알린다", async () => {
    const onSeatNavigate = vi.fn();
    render(
      <MemoryRouter>
        <ResultTable
          caption="결과 1건"
          columns={screeningColumns}
          rows={[["별의 항해", "2026-11-18 20:20", 129]]}
          seatLinks={[{ kind: "link", href: "/screenings/769004021" }]}
          onSeatNavigate={onSeatNavigate}
        />
      </MemoryRouter>,
    );
    const link = screen.getByRole("link", { name: "11/18 20:20 좌석 고르기" });
    // jsdom 은 새 탭을 열지 못한다 — 브라우저 기본 동작(이동)만 막는다. 우리 onClick 은 그대로 불린다
    link.addEventListener("click", (event) => event.preventDefault());
    const user = userEvent.setup();
    for (const key of ["Control", "Meta", "Shift"]) {
      await user.keyboard(`{${key}>}`);
      await user.click(link);
      await user.keyboard(`{/${key}}`);
    }
    expect(onSeatNavigate).not.toHaveBeenCalled();

    await user.click(link);
    expect(onSeatNavigate).toHaveBeenCalledTimes(1);
  });

  it("누를 수 있는 시각이 하나도 없으면(전부 매진·마감) 안내를 그리지 않는다", () => {
    render(
      <ResultTable
        caption="결과 2건"
        columns={screeningColumns}
        rows={[
          ["별의 항해", "2026-11-18 20:20", 0],
          ["한강의 밤", "2026-11-18 19:42", 10],
        ]}
        seatLinks={[
          { kind: "blocked", text: "매진" },
          { kind: "blocked", text: "마감" },
        ]}
      />,
    );
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByText(HINT)).not.toBeInTheDocument();
    expect(screen.getByText("마감")).toBeInTheDocument();
  });

  it("좌석 링크가 없는 답(집계 등)은 표가 그대로다 — 링크도 안내도 없다", () => {
    render(
      <ResultTable
        caption="결과 1건"
        columns={[{ name: "screening_count", type: "integer" }]}
        rows={[[12]]}
        seatLinks={null}
      />,
    );
    expect(screen.getAllByRole("columnheader")).toHaveLength(1);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByText(HINT)).not.toBeInTheDocument();
  });

  it("시작 시각 열이 없는 표는 링크 정보가 와도 링크·안내를 그리지 않는다", () => {
    render(
      <ResultTable
        caption="결과 1건"
        columns={[{ name: "movie_title", type: "text" }]}
        rows={[["별의 항해"]]}
        seatLinks={[{ kind: "link", href: "/screenings/1" }]}
      />,
    );
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByText(HINT)).not.toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "별의 항해" })).toBeInTheDocument();
  });
});
