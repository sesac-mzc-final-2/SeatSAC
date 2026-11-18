import { render, screen } from "@testing-library/react";
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
});
