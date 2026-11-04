import { useState } from "react";
import { Icon } from "@/shared/ui";
import styles from "./TheaterRankList.module.css";

export interface RankedTheater {
  theaterId: number;
  name: string;
}

interface Props {
  items: RankedTheater[];
  onMove: (from: number, to: number) => void;
}

/** 선호 극장 순위 (최대 3곳): 끌어서 놓기 + 위/아래 버튼(키보드) */
export function TheaterRankList({ items, onMove }: Props) {
  const [dragging, setDragging] = useState<number | null>(null);

  if (items.length === 0) {
    return <p className={styles.empty}>위의 극장을 눌러 최대 3곳까지 골라 주세요.</p>;
  }

  return (
    <ol className={styles.list} aria-label="선호 극장 순위">
      {items.map((theater, index) => (
        <li
          key={theater.theaterId}
          className={styles.item}
          draggable
          data-dragging={dragging === index || undefined}
          onDragStart={(event) => {
            event.dataTransfer.effectAllowed = "move";
            setDragging(index);
          }}
          onDragEnd={() => setDragging(null)}
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault();
            if (dragging !== null) onMove(dragging, index);
            setDragging(null);
          }}
        >
          <span className={styles.handle} aria-hidden="true">
            <Icon name="grip-vertical" size={18} strokeWidth={2.4} />
          </span>
          <b className={styles.rank}>{String(index + 1).padStart(2, "0")}</b>
          <span className={styles.name}>{theater.name}</span>
          <span className={styles.moves}>
            <button
              type="button"
              className={styles.move}
              onClick={() => onMove(index, index - 1)}
              disabled={index === 0}
              aria-label={`${theater.name} 순위 올리기`}
            >
              <Icon name="chevron-up" size={16} />
            </button>
            <button
              type="button"
              className={styles.move}
              onClick={() => onMove(index, index + 1)}
              disabled={index === items.length - 1}
              aria-label={`${theater.name} 순위 내리기`}
            >
              <Icon name="chevron-down" size={16} />
            </button>
          </span>
        </li>
      ))}
    </ol>
  );
}
