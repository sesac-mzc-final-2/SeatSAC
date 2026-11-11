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
            {columns.map((column) => (
              <th key={column.name} scope="col" data-numeric={isNumericColumn(column) || undefined}>
                {columnLabel(column)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {columns.map((column, columnIndex) => (
                <td key={column.name} data-numeric={isNumericColumn(column) || undefined}>
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
