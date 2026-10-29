import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { AuditoriumLayout, LayoutSeat, SeatRenderState } from "../model/types";
import { SeatMap } from "./SeatMap";

const seat = (seatId: number, over: Partial<LayoutSeat> = {}): LayoutSeat => ({
  seatId,
  seatLabel: `A${seatId}`,
  rowLabel: "A",
  colNo: seatId,
  x: seatId * 24,
  y: 0,
  w: 20,
  h: 20,
  seatType: "NORMAL",
  active: true,
  ...over,
});

const layout: AuditoriumLayout = {
  auditoriumId: 4060,
  name: "1관",
  screenType: "NORMAL",
  layoutVersion: 1,
  width: 160,
  height: 60,
  screen: { xFrom: 20, xTo: 140 },
  entrances: [{ x: 10, y: 50 }],
  verticalAisles: [{ xFrom: 70, xTo: 78 }],
  horizontalAisles: [{ yFrom: 28, yTo: 34 }],
  seats: [
    seat(1),
    seat(2, { seatLabel: "A2" }),
    seat(3, { seatLabel: "A3" }),
    seat(4, { seatLabel: "A4", active: false }),
  ],
  source: { provider: "LOTTE", fetchedAt: "2026-10-14T12:00:00+09:00" },
};

const stateOf = (states: Record<number, SeatRenderState>) => (target: LayoutSeat) => states[target.seatId];

describe("SeatMap (LAY-08)", () => {
  it("상영관 이름으로 배치도를 알려 주고 스크린 위치를 그린다", () => {
    render(<SeatMap layout={layout} stateOf={() => "available"} />);
    expect(screen.getByRole("group", { name: "1관 좌석 배치도" })).toBeInTheDocument();
    expect(screen.getByText("SCREEN")).toBeInTheDocument();
    expect(screen.getByText("입구")).toBeInTheDocument();
  });

  it("고를 수 있는 좌석만 체크박스로 읽히고, 나머지는 상태를 이름에 넣는다", () => {
    render(
      <SeatMap
        layout={layout}
        stateOf={stateOf({ 1: "available", 2: "held", 3: "reserved", 4: "inactive" })}
        onSelect={() => {}}
      />,
    );
    expect(screen.getAllByRole("checkbox")).toHaveLength(1);
    expect(screen.getByRole("checkbox", { name: "A1 선택 가능" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "A2 다른 사람이 선점 중" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "A3 예매 완료" })).toBeInTheDocument();
    // 판매하지 않는 자리는 고를 일이 없어 읽어 주지 않는다
    expect(screen.queryByRole("img", { name: /A4/ })).not.toBeInTheDocument();
  });

  it("선택한 좌석은 aria-checked로 표시한다", () => {
    render(
      <SeatMap
        layout={layout}
        stateOf={stateOf({ 1: "selected", 2: "available", 3: "available", 4: "inactive" })}
        onSelect={() => {}}
      />,
    );
    expect(screen.getByRole("checkbox", { name: "A1 선택함" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "A2 선택 가능" })).not.toBeChecked();
  });

  it("누르면 그 좌석을 넘긴다", async () => {
    const onSelect = vi.fn();
    render(
      <SeatMap
        layout={layout}
        stateOf={stateOf({ 1: "available", 2: "available", 3: "available", 4: "inactive" })}
        onSelect={onSelect}
      />,
    );
    await userEvent.click(screen.getByRole("checkbox", { name: "A2 선택 가능" }));
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect.mock.calls[0][0]).toMatchObject({ seatId: 2, seatLabel: "A2" });
  });

  it("키보드(Enter·Space)로도 고를 수 있다", async () => {
    const onSelect = vi.fn();
    render(<SeatMap layout={layout} stateOf={() => "available"} onSelect={onSelect} />);
    const first = screen.getByRole("checkbox", { name: "A1 선택 가능" });
    first.focus();
    await userEvent.keyboard("{Enter}");
    await userEvent.keyboard(" ");
    expect(onSelect).toHaveBeenCalledTimes(2);
  });

  it("선점 중·예매 완료 좌석은 눌러도 아무 일이 없다", async () => {
    const onSelect = vi.fn();
    render(
      <SeatMap
        layout={layout}
        stateOf={stateOf({ 1: "held", 2: "reserved", 3: "inactive", 4: "inactive" })}
        onSelect={onSelect}
      />,
    );
    await userEvent.click(screen.getByRole("img", { name: "A1 다른 사람이 선점 중" }));
    await userEvent.click(screen.getByRole("img", { name: "A2 예매 완료" }));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("disabled면 전체가 잠겨 체크박스가 없다", async () => {
    const onSelect = vi.fn();
    render(<SeatMap layout={layout} stateOf={() => "available"} onSelect={onSelect} disabled />);
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
    await userEvent.click(screen.getByRole("img", { name: "A1 선택 가능" }));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("onSelect가 없으면 보기 전용이다", () => {
    render(<SeatMap layout={layout} stateOf={() => "available"} />);
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
  });
});
