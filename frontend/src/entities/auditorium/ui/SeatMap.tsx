import { type KeyboardEvent, useMemo } from "react";
import {
  MAP_PADDING,
  isSelectable,
  layoutViewBox,
  rowMarkers,
  seatAriaLabel,
  seatFontSize,
  toPixelLayout,
  viewBoxAttr,
} from "../lib/seat-map";
import type { AuditoriumLayout, LayoutSeat, SeatRenderState } from "../model/types";
import styles from "./SeatMap.module.css";

interface Props {
  layout: AuditoriumLayout;
  /** 좌석마다 어떻게 그릴지. 점유 상태(BKG-01)를 아는 쪽이 넘긴다 */
  stateOf: (seat: LayoutSeat) => SeatRenderState;
  /** 고를 수 있는 좌석을 눌렀을 때. 없으면 보기 전용 */
  onSelect?: (seat: LayoutSeat) => void;
  /** 상영이 시작됐거나 이미 선점한 예매가 있을 때처럼 전체를 잠글 때 */
  disabled?: boolean;
}

/**
 * LAY-08 좌석 배치도 — CAT-04 좌표를 그대로 SVG로 그린다. 좌석 점유·선택 규칙은 모르고,
 * 「이 좌석을 어떤 상태로 그릴지」만 {@link Props.stateOf}로 받는다.
 *
 * <p>좌석이 100개를 넘고 상영관마다 모양이 달라서 격자(행×열)로 그리지 않는다 — 수집한 x·y·w·h를
 * 그대로 쓰고 통로·입구·스크린도 좌표대로 둔다. 그래야 실제 상영관과 같은 모양이 된다.
 */
export function SeatMap({ layout: source, stateOf, onSelect, disabled = false }: Props) {
  // 좌표를 px로 바꿔 두면 여백·스크린·입구·글자를 px로 정할 수 있다 (toPixelLayout 참고)
  const layout = useMemo(() => toPixelLayout(source), [source]);
  const box = layoutViewBox(layout);
  const rows = rowMarkers(layout.seats);
  // 스크린 글자(12px)가 위 여백 안에 들어가게 둔다 — 글자는 막대 8px 위에 놓인다
  const screenY = -MAP_PADDING.top + 26;

  const press = (seat: LayoutSeat, state: SeatRenderState) => {
    if (disabled || !onSelect || !isSelectable(state)) return;
    onSelect(originalOf(seat));
  };

  // stateOf·onSelect는 원래 좌석을 받는다 — 좌표만 바꾼 사본을 밖으로 내보내지 않는다
  const original = useMemo(() => new Map(source.seats.map((seat) => [seat.seatId, seat])), [source]);
  const originalOf = (seat: LayoutSeat): LayoutSeat => original.get(seat.seatId) ?? seat;

  const onKeyDown = (event: KeyboardEvent<SVGGElement>, seat: LayoutSeat, state: SeatRenderState) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    event.preventDefault();
    press(seat, state);
  };

  return (
    <svg
      className={styles.map}
      viewBox={viewBoxAttr(box)}
      width={box.width}
      height={box.height}
      // 작은 상영관을 화면 폭만큼 부풀리지 않고, 좁은 화면에서는 좌석이 읽히는 크기 아래로 줄이지 않는다(가로 스크롤)
      style={{ maxWidth: box.width, minWidth: box.width * 0.72 }}
      preserveAspectRatio="xMidYMid meet"
      role="group"
      aria-label={`${layout.name} 좌석 배치도`}
      data-disabled={disabled || undefined}
    >
      {/* 스크린 — 어느 쪽이 앞인지 알려 준다 */}
      <g className={styles.screen} aria-hidden="true">
        <rect
          x={layout.screen.xFrom}
          y={screenY}
          width={Math.max(layout.screen.xTo - layout.screen.xFrom, 1)}
          height={6}
          rx={3}
        />
        <text x={(layout.screen.xFrom + layout.screen.xTo) / 2} y={screenY - 8} textAnchor="middle">
          SCREEN
        </text>
      </g>

      {/* 통로 — 좌석 사이 빈 구간을 옅게 칠해 줄을 찾기 쉽게 한다 */}
      <g className={styles.aisles} aria-hidden="true">
        {layout.verticalAisles.map((aisle) => (
          <rect
            key={`v-${aisle.xFrom}-${aisle.xTo}`}
            x={aisle.xFrom}
            y={0}
            width={Math.max(aisle.xTo - aisle.xFrom, 0)}
            height={box.height - MAP_PADDING.top - MAP_PADDING.bottom}
          />
        ))}
        {layout.horizontalAisles.map((aisle) => (
          <rect
            key={`h-${aisle.yFrom}-${aisle.yTo}`}
            x={0}
            y={aisle.yFrom}
            width={box.width - MAP_PADDING.left - MAP_PADDING.right}
            height={Math.max(aisle.yTo - aisle.yFrom, 0)}
          />
        ))}
      </g>

      {/* 줄 이름 */}
      <g className={styles.rows} aria-hidden="true">
        {rows.map((row) => (
          <text
            key={row.rowLabel}
            x={-10}
            y={row.centerY}
            textAnchor="end"
            dominantBaseline="central"
            fontSize={13}
          >
            {row.rowLabel}
          </text>
        ))}
      </g>

      {/* 입구 */}
      <g className={styles.entrances} aria-hidden="true">
        {layout.entrances.map((entrance) => (
          <g key={`${entrance.x}-${entrance.y}`}>
            <circle cx={entrance.x} cy={entrance.y} r={5} />
            <text x={entrance.x} y={entrance.y + 16} textAnchor="middle">
              입구
            </text>
          </g>
        ))}
      </g>

      {/* 좌석 */}
      <g>
        {layout.seats.map((seat) => {
          const state = stateOf(originalOf(seat));
          const selectable = !disabled && Boolean(onSelect) && isSelectable(state);
          return (
            <g
              key={seat.seatId}
              className={styles.seat}
              data-state={state}
              data-seat-type={seat.seatType}
              data-selectable={selectable || undefined}
              role={selectable ? "checkbox" : "img"}
              aria-checked={selectable ? state === "selected" : undefined}
              aria-hidden={state === "inactive" ? true : undefined}
              aria-label={state === "inactive" ? undefined : seatAriaLabel(seat, state)}
              tabIndex={selectable ? 0 : undefined}
              onClick={() => press(seat, state)}
              onKeyDown={(event) => onKeyDown(event, seat, state)}
            >
              <rect x={seat.x} y={seat.y} width={seat.w} height={seat.h} rx={Math.min(seat.w, seat.h) * 0.22} />
              <text
                x={seat.x + seat.w / 2}
                y={seat.y + seat.h / 2}
                textAnchor="middle"
                dominantBaseline="central"
                fontSize={seatFontSize(seat)}
              >
                {seat.seatLabel}
              </text>
            </g>
          );
        })}
      </g>
    </svg>
  );
}
