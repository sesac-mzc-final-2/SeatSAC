import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/shared/api";
import { ErrorState } from "./ErrorState";

describe("ErrorState", () => {
  afterEach(() => vi.restoreAllMocks());

  it("code로 문구를 고르고 message는 보여 주지 않는다", () => {
    const error = new ApiError(503, {
      code: "SERVICE_UNAVAILABLE",
      message: "upstream timeout (dev only)",
      traceId: "0123456789abcdef0123456789abcdef",
      details: [],
    });
    render(<ErrorState error={error} />);
    expect(screen.getByRole("alert")).toHaveTextContent("서비스가 잠시 응답하지 않습니다");
    expect(screen.queryByText(/upstream timeout/)).not.toBeInTheDocument();
  });

  it("문의 코드는 앞 8자리를 보여 주고 누르면 32자리를 복사한다", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const error = new ApiError(500, {
      code: "INTERNAL_ERROR",
      message: "",
      traceId: "0123456789abcdef0123456789abcdef",
      details: [],
    });
    render(<ErrorState error={error} />);
    const button = screen.getByRole("button", { name: /문의 코드 01234567/ });
    expect(button).toHaveTextContent("01234567");
    expect(button).not.toHaveTextContent("89abcdef0123456789abcdef");
    await userEvent.click(button);
    expect(writeText).toHaveBeenCalledWith("0123456789abcdef0123456789abcdef");
    expect(button).toHaveTextContent("복사됨");
  });

  it("traceId가 없으면(네트워크 오류) 문의 코드를 숨긴다", () => {
    render(<ErrorState error={new TypeError("Failed to fetch")} />);
    expect(screen.queryByRole("button", { name: /문의 코드/ })).not.toBeInTheDocument();
  });
});
