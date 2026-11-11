import type { ChatCell, ChatColumn } from "@/entities/ai-chat";
import { columnLabel, formatCell, isNumericColumn } from "../lib/result-cells";
import styles from "./ResultTable.module.css";

interface Props {
  columns: ChatColumn[];
  rows: ChatCell[][];
  caption: string;
}

/** 답의 결과 표 (최대 500행). 대화창 폭에 맞춰 가로·세로로 스크롤하고 머리글은 고정 */
export function ResultTable({ columns, rows, caption }: Props) {
  return (
    <div className={styles.scroll} tabIndex={0} role="region" aria-label={caption}>
      <table className={styles.table}>
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {/* 열 이름은 SQL 별칭이라 겹칠 수 있다(별칭 없는 t.name, a.name) → key 에 위치를 섞는다 */}
            {columns.map((column, columnIndex) => (
              <th key={`${columnIndex}-${column.name}`} scope="col" data-numeric={isNumericColumn(column) || undefined}>
                {columnLabel(column)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {columns.map((column, columnIndex) => (
                <td key={`${columnIndex}-${column.name}`} data-numeric={isNumericColumn(column) || undefined}>
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
