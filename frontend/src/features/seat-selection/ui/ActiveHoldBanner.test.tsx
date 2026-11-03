import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";
import { ActiveHoldBanner } from "./ActiveHoldBanner";

const renderBanner = (expiresAt: string | null) =>
  render(
    <MemoryRouter>
      <ActiveHoldBanner bookingId={7} expiresAt={expiresAt} payHref="/bookings/7/pay" onRelease={() => {}} releasing={false} />
    </MemoryRouter>,
  );

describe("ActiveHoldBanner", () => {
  it("만료 시각을 모르면 「거의 끝났어요」라고 하지 않는다", () => {
    renderBanner(null);
    expect(screen.queryByText(/거의 끝났어요/)).not.toBeInTheDocument();
    expect(screen.getByText(/이어서 결제하거나 선점을 해제해 주세요/)).toBeInTheDocument();
  });

  it("만료 시각이 지났으면 「거의 끝났어요」라고 알린다", () => {
    renderBanner("2020-01-01T00:00:00+09:00");
    expect(screen.getByText(/거의 끝났어요/)).toBeInTheDocument();
  });
});
