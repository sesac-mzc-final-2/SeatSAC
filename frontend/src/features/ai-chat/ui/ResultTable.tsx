import type { ChatCell, ChatColumn } from "@/entities/ai-chat";
import { columnLabel, formatCell, isNumericColumn, wrapColumnIndex } from "../lib/result-cells";
import styles from "./ResultTable.module.css";

interface Props {
  columns: ChatColumn[];
  rows: ChatCell[][];
  caption: string;
}

/**
 * 답의 결과 표 (최대 500행). 세로로 스크롤하고 머리글은 고정.
 * 열 하나(영화 제목 등)만 줄바꿈해 대화창 폭에 맞춘다 — 시작 시각·잔여석이 가로 스크롤 뒤에 숨지 않게.
 * 열이 아주 많아 그래도 넘치면 가로 스크롤이 남는다
 */
export function ResultTable({ columns, rows, caption }: Props) {
  const wrapIndex = wrapColumnIndex(columns);
  return (
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
              {columns.map((column, columnIndex) => (
                <td
                  key={`${columnIndex}-${column.name}`}
                  data-numeric={isNumericColumn(column) || undefined}
                  data-wrap={columnIndex === wrapIndex || undefined}
                >
                  {formatCell(column, row[columnIndex] ?? null)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
