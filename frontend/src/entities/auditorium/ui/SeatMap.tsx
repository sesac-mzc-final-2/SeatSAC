import { type KeyboardEvent, useId, useMemo } from "react";
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
  const contentWidth = box.width - MAP_PADDING.left - MAP_PADDING.right;
  // 곡선 스크린(시안): 스크린 구간 양 끝에서 위로 휘는 선. 글자는 곡선 아래 가운데
  const screenFrom = layout.screen.xFrom;
  const screenTo = Math.max(layout.screen.xTo, screenFrom + 1);
  const screenY = -MAP_PADDING.top + 30;
  const screenMid = (screenFrom + screenTo) / 2;
  // 사선 무늬 id는 화면에 배치도가 여럿이어도 겹치지 않게
  const heldPatternId = `seat-held-${useId().replace(/:/g, "")}`;

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
      <defs>
        {/* 다른 사람이 선점 중 = 어두운 칸 + 사선 (시안 좌석 상태 색) */}
        {/* 범례 칩과 같은 사선: 4px마다 어두운 3px + 밝은 1px */}
        <pattern id={heldPatternId} width="4" height="4" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <rect width="4" height="4" className={styles.heldBase} />
          <line x1="0.5" y1="0" x2="0.5" y2="4" className={styles.heldStripe} />
        </pattern>
      </defs>

      {/* 스크린 — 어느 쪽이 앞인지 알려 준다 (곡선) */}
      <g className={styles.screen} aria-hidden="true">
        <path d={`M ${screenFrom} ${screenY + 12} Q ${screenMid} ${screenY - 12} ${screenTo} ${screenY + 12}`} />
        <text x={screenMid} y={screenY + 22} textAnchor="middle">
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
            width={contentWidth}
            height={Math.max(aisle.yTo - aisle.yFrom, 0)}
          />
        ))}
      </g>

      {/* 줄 이름 — 양쪽 (시안) */}
      <g className={styles.rows} aria-hidden="true">
        {rows.map((row) => (
          <g key={row.rowLabel}>
            <text x={-12} y={row.centerY} textAnchor="end" dominantBaseline="central">
              {row.rowLabel}
            </text>
            <text x={contentWidth + 12} y={row.centerY} textAnchor="start" dominantBaseline="central">
              {row.rowLabel}
            </text>
          </g>
        ))}
      </g>

      {/* 출입구 — 문 아이콘 + EXIT */}
      <g className={styles.entrances} aria-hidden="true">
        {layout.entrances.map((entrance) => (
          <g key={`${entrance.x}-${entrance.y}`} transform={`translate(${entrance.x - 8} ${entrance.y - 8})`}>
            <path d="M9 2.7h2.2a1.3 1.3 0 0 1 1.3 1.3v9.3M1.3 13.3h13.4M3.3 13.3V3.7l5.4-1.7v11.3M6.7 8h.01" />
            <text x={8} y={26} textAnchor="middle">
              EXIT
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
              <rect
                x={seat.x}
                y={seat.y}
                width={seat.w}
                height={seat.h}
                rx={Math.min(seat.w, seat.h) * 0.2}
                // fill 속성은 CSS(.seat rect { fill: transparent })에 져서 사선이 안 보인다 → 인라인 스타일
                style={state === "held" ? { fill: `url(#${heldPatternId})` } : undefined}
              />
              {state === "reserved" ? (
                // 예매 완료 = 어두운 칸 + X (시안)
                <path
                  className={styles.reservedMark}
                  d={reservedMark(seat)}
                />
              ) : (
                <text
                  x={seat.x + seat.w / 2}
                  y={seat.y + seat.h / 2}
                  textAnchor="middle"
                  dominantBaseline="central"
                  fontSize={seatFontSize(seat)}
                >
                  {seat.seatLabel}
                </text>
              )}
            </g>
          );
        })}
      </g>
    </svg>
  );
}

/** 예매 완료 좌석의 X — 좌석 가운데 짧은 변의 30% 크기 */
function reservedMark(seat: LayoutSeat): string {
  const cx = seat.x + seat.w / 2;
  const cy = seat.y + seat.h / 2;
  const r = Math.min(seat.w, seat.h) * 0.18;
  return `M ${cx - r} ${cy - r} L ${cx + r} ${cy + r} M ${cx + r} ${cy - r} L ${cx - r} ${cy + r}`;
}
