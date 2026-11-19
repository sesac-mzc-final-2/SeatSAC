import { Link } from "react-router";
import type { ChatCell, ChatColumn } from "@/entities/ai-chat";
import { columnLabel, formatCell, isNumericColumn, wrapColumnIndex } from "../lib/result-cells";
import { SEAT_LINK_COLUMN, type SeatLink } from "../lib/seat-links";
import styles from "./ResultTable.module.css";

interface Props {
  columns: ChatColumn[];
  rows: ChatCell[][];
  caption: string;
  /** 행마다 좌석 선택 화면 링크(`rows` 와 같은 순서). 있으면 그 행의 시작 시각 칸이 링크가 된다 */
  seatLinks?: (SeatLink | null)[] | null;
  /** 좌석 링크를 눌렀을 때 (대화창 닫기 — 좁은 화면에서 창이 좌석 화면을 가리지 않게) */
  onSeatNavigate?: () => void;
}

/**
 * 답의 결과 표 (최대 500행). 세로로 스크롤하고 머리글은 고정.
 * 글자 열은 띄어쓰기에서 줄을 바꾸고, 긴 글자 열 하나(영화 제목 등)는 최소 폭을 둔다 — 대화창 폭에 맞춰
 * 시작 시각·잔여석이 가로 스크롤 뒤에 숨지 않게.
 * 열이 아주 많아 그래도 넘치면 가로 스크롤이 남는다.
 * 회차 한 줄인 답은 열을 더 두지 않고 시작 시각 글자를 그 회차의 좌석 선택 화면 링크로 만든다.
 * 매진·마감 회차는 링크 없이 흐린 글자(마감은 작은 「마감」 표시)
 */
export function ResultTable({ columns, rows, caption, seatLinks, onSeatNavigate }: Props) {
  const wrapIndex = wrapColumnIndex(columns);
  // 같은 이름의 열이 둘이어도 링크는 첫 번째 시작 시각 칸에만 건다(판정에 쓴 칸)
  const seatIndex = seatLinks ? columns.findIndex((column) => column.name === SEAT_LINK_COLUMN) : -1;
  const hasSeatLink = seatIndex >= 0 && !!seatLinks?.some((link) => link?.kind === "link");
  return (
    <>
      <div className={styles.scroll} tabIndex={0} role="region" aria-label={caption}>
        <table className={styles.table}>
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr>
              {/* 열 이름은 SQL 별칭이라 겹칠 수 있다(별칭 없는 t.name, a.name) → key 에 위치를 섞는다 */}
              {columns.map((column, columnIndex) => (
                <th
                  key={`${columnIndex}-${column.name}`}
                  scope="col"
                  data-numeric={isNumericColumn(column) || undefined}
                  data-wrap={columnIndex === wrapIndex || undefined}
                >
                  {columnLabel(column)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={rowIndex}>
                {columns.map((column, columnIndex) => {
                  const text = formatCell(column, row[columnIndex] ?? null);
                  const seat = columnIndex === seatIndex ? (seatLinks?.[rowIndex] ?? null) : null;
                  return (
                    <td
                      key={`${columnIndex}-${column.name}`}
                      data-numeric={isNumericColumn(column) || undefined}
                      data-wrap={columnIndex === wrapIndex || undefined}
                    >
                      {seat ? seatCell(seat, text, onSeatNavigate) : text}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {hasSeatLink && <p className={styles.hint}>시각을 누르면 좌석 선택으로 이동해요</p>}
    </>
  );
}

/** 시작 시각 칸: 예매할 수 있으면 시각 글자가 링크, 매진·마감이면 흐린 글자 */
function seatCell(seat: SeatLink, text: string, onNavigate?: () => void) {
  if (seat.kind === "link") {
    // 보이는 글자(시각)를 이름 앞에 둔다 — 음성 명령으로 보이는 글자를 불러도 찾게 (WCAG 2.5.3)
    return (
      <Link className={styles.seatLink} to={seat.href} aria-label={`${text} 좌석 고르기`} onClick={onNavigate}>
        {text}
      </Link>
    );
  }
  return (
    <span className={styles.blocked}>
      {text}
      {seat.text === "마감" ? (
        <span className={styles.closed}>마감</span>
      ) : (
        // 매진은 잔여석 열의 0 이 이미 보인다 — 화면을 읽는 사람에게만 따로 알린다
        <span className="sr-only"> 매진</span>
      )}
    </span>
  );
}
