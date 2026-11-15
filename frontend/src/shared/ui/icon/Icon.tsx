import type { SVGProps } from "react";

/** 화면에 쓰는 선 아이콘 몇 개. 외부 아이콘 패키지 없이 24×24 stroke 경로만 둔다 */
const PATHS = {
  "arrow-right": "M5 12h14M13 6l6 6-6 6",
  "arrow-left": "M19 12H5M11 18l-6-6 6-6",
  "arrow-up-right": "M7 17 17 7M8 7h9v9",
  "chevron-right": "m9 6 6 6-6 6",
  "chevron-left": "m15 6-6 6 6 6",
  "chevron-down": "m6 9 6 6 6-6",
  "chevron-up": "m6 15 6-6 6 6",
  film: "M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1ZM7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4",
  clock: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 7v5l3 2",
  calendar: "M8 3v3M16 3v3M4 9h16M6 5h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z",
  "map-pin": "M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11ZM12 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z",
  armchair: "M6 13V7a3 3 0 0 1 3-3h6a3 3 0 0 1 3 3v6M4 12v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5M6 13h12M7 19v2M17 19v2",
  "alert-circle": "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 8v4M12 16h.01",
  "rotate-cw": "M21 12a9 9 0 1 1-2.6-6.4M21 4v5h-5",
  "log-in": "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3",
  check: "m5 12 5 5L20 7",
  copy: "M9 9h10v10H9zM5 15V5h10",
  x: "M18 6 6 18M6 6l12 12",
  timer: "M10 2h4M12 14l3-3M12 22a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z",
  "door-open": "M13 4h3a2 2 0 0 1 2 2v14M2 20h20M5 20V5.5l8-2.5v17M10 12h.01",
  info: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 16v-4M12 8h.01",
  "credit-card": "M3 6h18a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1ZM2 10h20",
  "shield-check": "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10ZM9 12l2 2 4-4",
  loader: "M21 12a9 9 0 1 1-6.2-8.56",
  user: "M20 21a8 8 0 1 0-16 0M12 13a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z",
  ticket: "M3 9V7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4ZM13 5v2M13 11v2M13 17v2",
  sparkles: "m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3ZM19 17v4M17 19h4M5 3v2M4 4h2",
  "grip-vertical": "M9 5h.01M9 12h.01M9 19h.01M15 5h.01M15 12h.01M15 19h.01",
  trash: "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3",
  save: "M5 4h11l3 3v13H5zM8 4v5h7V4M8 20v-6h8v6",
  send: "M22 2 11 13M22 2l-7 20-4-9-9-4 20-7Z",
  "message-square": "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z",
  plus: "M12 5v14M5 12h14",
  /** 내 선호(극장·시간대) 표시. 채워 쓰려면 `fill="currentColor"` */
  heart:
    "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z",
} as const;

export type IconName = keyof typeof PATHS;

interface Props extends Omit<SVGProps<SVGSVGElement>, "name"> {
  name: IconName;
  size?: number;
  strokeWidth?: number;
}

export function Icon({ name, size = 16, strokeWidth = 1.8, ...rest }: Props) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
