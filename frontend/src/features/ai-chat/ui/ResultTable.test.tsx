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
});
