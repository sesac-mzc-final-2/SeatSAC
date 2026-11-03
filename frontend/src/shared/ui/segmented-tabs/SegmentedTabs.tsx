import type { ReactNode } from "react";
import styles from "./SegmentedTabs.module.css";

export interface SegmentedTab<T extends string> {
  value: T;
  label: ReactNode;
  /** 탭이 제어하는 패널 id (aria-controls) */
  panelId?: string;
}

interface Props<T extends string> {
  tabs: ReadonlyArray<SegmentedTab<T>>;
  value: T;
  onChange: (value: T) => void;
  "aria-label": string;
}

/** 알약 모양 탭 (마이페이지 예매 내역 · 선호 설정). 좌우 화살표로 옮길 수 있다 */
export function SegmentedTabs<T extends string>({ tabs, value, onChange, "aria-label": ariaLabel }: Props<T>) {
  const move = (delta: number) => {
    const index = tabs.findIndex((tab) => tab.value === value);
    const next = tabs[(index + delta + tabs.length) % tabs.length];
    if (!next) return;
    onChange(next.value);
    // roving tabindex: 이전 탭은 tabIndex -1 이 되므로 새 탭으로 포커스를 옮겨야 이름이 읽힌다
    document.getElementById(`tab-${next.value}`)?.focus();
  };

  return (
    <div
      className={styles.tabs}
      role="tablist"
      aria-label={ariaLabel}
      onKeyDown={(event) => {
        if (event.key === "ArrowRight") {
          event.preventDefault();
          move(1);
        } else if (event.key === "ArrowLeft") {
          event.preventDefault();
          move(-1);
        }
      }}
    >
      {tabs.map((tab) => {
        const selected = tab.value === value;
        return (
          <button
            key={tab.value}
            type="button"
            role="tab"
            id={`tab-${tab.value}`}
            className={styles.tab}
            aria-selected={selected}
            aria-controls={tab.panelId}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(tab.value)}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}
