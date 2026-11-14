import { useEffect, useState } from "react";
import { Icon } from "@/shared/ui";
import styles from "./HomeHero.module.css";

/** 자동으로 다음 소개로 넘어가는 간격 */
const AUTO_SLIDE_MS = 5000;

const SEAT_COLS = 8;
const SEAT_ROWS = 5;

type Slide = {
  kicker: string;
  headline: readonly [string, string];
  description: string;
  /** 오른쪽 좌석 그림에서 내가 잡은 좌석 · 다른 사람이 잡은 좌석 (0부터, 왼쪽 위에서 오른쪽으로) */
  mine: readonly number[];
  taken: readonly number[];
};

/** 영화가 아니라 서비스가 하는 일을 소개한다 — 영화는 바로 아래 상영중 목록이 보여 준다 */
const SLIDES: readonly Slide[] = [
  {
    kicker: "좌석 선점",
    headline: ["고른 좌석은", "5분간 내 자리."],
    description: "좌석을 고르는 순간 선점됩니다.\n5분 안에 모의 결제를 마치면 예매 완료.",
    mine: [19, 20],
    taken: [3, 4, 10, 26, 27, 28, 33],
  },
  {
    kicker: "실시간 반영",
    headline: ["다른 사람의 선택도", "1초 안에 보여요."],
    description: "누군가 좌석을 잡거나 놓으면\n열려 있는 화면에 바로 반영됩니다.",
    mine: [],
    taken: [3, 4, 10, 11, 12, 18, 19, 20, 21, 26, 27, 28, 33, 34],
  },
  {
    kicker: "상영시간표",
    headline: ["4개 극장의 편성을", "한눈에."],
    description: "극장·날짜·영화로 회차를 찾고\n남은 좌석을 확인한 뒤 바로 좌석 선택으로.",
    mine: [],
    taken: [],
  },
];

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/** W1 상단 히어로: 서비스 소개 3장을 5초마다 돌려 가며 보여 준다 */
export function HomeHero() {
  const [index, setIndex] = useState(0);
  // 마우스를 올렸거나 안쪽에 포커스가 있으면 자동 넘김을 멈춘다 (읽거나 누르는 중에 바뀌지 않게).
  // 둘을 따로 기억한다 — 포커스가 안에 남아 있는데 마우스만 나갔다고 다시 돌지 않게
  const [hovered, setHovered] = useState(false);
  const [focused, setFocused] = useState(false);
  const paused = hovered || focused;
  // 직접 넘긴 소개만 스크린리더에 읽어 준다. 자동 넘김까지 읽으면 5초마다 끼어든다
  const [announce, setAnnounce] = useState(false);
  const count = SLIDES.length;
  const slide = SLIDES[index];

  useEffect(() => {
    if (paused || prefersReducedMotion()) return;
    // index 가 바뀔 때마다 다시 걸어, 직접 넘긴 직후에도 5초를 온전히 보여 준다
    const timer = window.setTimeout(() => {
      setAnnounce(false);
      setIndex((index + 1) % count);
    }, AUTO_SLIDE_MS);
    return () => window.clearTimeout(timer);
  }, [count, index, paused]);

  const show = (next: number) => {
    setAnnounce(true);
    setIndex(next);
  };

  return (
    <section
      className={styles.hero}
      aria-label="서비스 소개"
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false);
      }}
    >
      {/* live region 은 요소가 유지돼야 읽힌다 → 바깥은 그대로 두고 안쪽만 key 로 바꿔 등장 효과를 준다 */}
      <div className={styles.text} aria-live={announce ? "polite" : "off"} aria-atomic="true">
        <div key={index} className={styles.textInner}>
          <span className={styles.kicker}>{slide.kicker}</span>
          <h1 className={styles.headline}>
            {slide.headline.map((line) => (
              <span key={line}>{line}</span>
            ))}
          </h1>
          <p className={styles.description}>{slide.description}</p>
        </div>
      </div>
      <div className={styles.art} aria-hidden="true">
        <div className={styles.screen}>SCREEN</div>
        <div className={styles.seats} style={{ gridTemplateColumns: `repeat(${SEAT_COLS}, 1fr)` }}>
          {Array.from({ length: SEAT_COLS * SEAT_ROWS }, (_, i) => (
            <span key={i} className={slide.mine.includes(i) ? styles.mine : slide.taken.includes(i) ? styles.taken : undefined} />
          ))}
        </div>
      </div>
      <div className={styles.slider}>
        <div className={styles.lines}>
          {SLIDES.map((item, i) => (
            <button
              key={item.kicker}
              type="button"
              className={styles.line}
              aria-pressed={i === index}
              aria-label={`${item.kicker} 소개 보기`}
              onClick={() => show(i)}
            />
          ))}
        </div>
        <span>
          <b>{String(index + 1).padStart(2, "0")}</b> / {String(count).padStart(2, "0")}
        </span>
        <button type="button" className={styles.arrow} aria-label="이전 소개" onClick={() => show((index + count - 1) % count)}>
          <Icon name="chevron-left" size={18} />
        </button>
        <button type="button" className={styles.arrow} aria-label="다음 소개" onClick={() => show((index + 1) % count)}>
          <Icon name="chevron-right" size={18} />
        </button>
      </div>
    </section>
  );
}
