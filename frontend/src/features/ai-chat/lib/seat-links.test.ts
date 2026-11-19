import { describe, expect, it } from "vitest";
import { seatLinks } from "./seat-links";

const columns = [
  { name: "movie_title", type: "text" as const },
  { name: "starts_at", type: "text" as const },
  { name: "remaining_seats", type: "integer" as const },
];
const NOW = Date.parse("2026-11-18T19:40:00+09:00");

describe("seatLinks", () => {
  it("회차 ID가 있는 행은 좌석 선택 화면 링크", () => {
    const links = seatLinks(
      { columns, rows: [["별의 항해", "2026-11-18 20:20", 129]], screeningIds: [769004021] },
      NOW,
    );
    expect(links).toEqual([{ kind: "link", href: "/screenings/769004021" }]);
  });

  it("ai 가 회차 ID를 주지 않은 답(집계·예전 응답)은 링크가 없다", () => {
    const rows = [["별의 항해", "2026-11-18 20:20", 129]];
    expect(seatLinks({ columns, rows, screeningIds: null }, NOW)).toBeNull();
    expect(seatLinks({ columns, rows }, NOW)).toBeNull();
    expect(seatLinks({ columns: null, rows: null, screeningIds: null }, NOW)).toBeNull();
    // 행마다 null 뿐이어도 열을 두지 않는다
    expect(seatLinks({ columns, rows, screeningIds: [null] }, NOW)).toBeNull();
  });

  it("행 수와 ID 수가 다르면 어느 행의 ID 인지 믿을 수 없어 링크를 두지 않는다", () => {
    const rows = [
      ["별의 항해", "2026-11-18 20:20", 129],
      ["한강의 밤", "2026-11-18 21:00", 40],
    ];
    expect(seatLinks({ columns, rows, screeningIds: [1] }, NOW)).toBeNull();
  });

  it("ID 가 행보다 많아도 믿지 않는다", () => {
    const rows = [["별의 항해", "2026-11-18 20:20", 129]];
    expect(seatLinks({ columns, rows, screeningIds: [1, 2] }, NOW)).toBeNull();
  });

  it("양의 정수가 아닌 ID(문자열·소수·0·음수)는 링크를 만들지 않는다", () => {
    const rows = [
      ["별의 항해", "2026-11-18 20:20", 129],
      ["한강의 밤", "2026-11-18 21:00", 40],
      ["고양이 탐정", "2026-11-18 21:30", 40],
      ["철의 심장", "2026-11-18 22:00", 40],
      ["철의 심장", "2026-11-18 23:00", 40],
    ];
    const screeningIds = ["769004021", 1.5, 0, -3, 7] as unknown as number[];
    const links = seatLinks({ columns, rows, screeningIds }, NOW);
    expect(links?.slice(0, 4)).toEqual([null, null, null, null]);
    expect(links?.[4]).toMatchObject({ kind: "link", href: "/screenings/7" });
  });

  it("잔여석 0 이하는 매진, 잔여석을 모르면(null) 링크를 둔다", () => {
    const links = seatLinks(
      {
        columns,
        rows: [
          ["별의 항해", "2026-11-18 20:20", 0],
          ["한강의 밤", "2026-11-18 21:00", null],
          ["고양이 탐정", "2026-11-18 21:30", -1],
        ],
        screeningIds: [1, 2, 3],
      },
      NOW,
    );
    expect(links?.[0]).toEqual({ kind: "blocked", text: "매진" });
    expect(links?.[1]).toMatchObject({ kind: "link", href: "/screenings/2" });
    expect(links?.[2]).toEqual({ kind: "blocked", text: "매진" });
  });

  it("잔여석 열이 없는 회차 목록(잔여석을 묻지 않은 답)은 매진으로 보지 않고 링크를 둔다 — 마감은 그대로", () => {
    const listColumns = columns.slice(0, 2);
    const links = seatLinks(
      {
        columns: listColumns,
        rows: [
          ["별의 항해", "2026-11-18 20:20"],
          ["별의 항해", "2026-11-18 19:44"],
        ],
        screeningIds: [1, 2],
      },
      NOW,
    );
    expect(links).toEqual([
      { kind: "link", href: "/screenings/1" },
      { kind: "blocked", text: "마감" },
    ]);
  });

  it("시작 5분 전부터는 마감 (그 전까지는 링크)", () => {
    const row = (startsAt: string) => ({ columns, rows: [["별의 항해", startsAt, 10]], screeningIds: [1] });
    expect(seatLinks(row("2026-11-18 19:46"), NOW)?.[0]).toMatchObject({ kind: "link" });
    expect(seatLinks(row("2026-11-18 19:45"), NOW)?.[0]).toEqual({ kind: "blocked", text: "마감" });
    expect(seatLinks(row("2026-11-18 19:00"), NOW)?.[0]).toEqual({ kind: "blocked", text: "마감" });
  });

  it("회차를 알 수 없는 행(null)만 비우고 나머지는 링크", () => {
    const links = seatLinks(
      {
        columns: [
          { name: "theater_name", type: "text" },
          { name: "starts_at", type: "text" },
        ],
        rows: [
          ["강남", "2026-11-21 15:00"],
          ["건대입구", null],
        ],
        screeningIds: [9, null],
      },
      NOW,
    );
    expect(links).toEqual([{ kind: "link", href: "/screenings/9" }, null]);
  });

  it("시작 시각 열이 없으면 링크를 걸 칸이 없다 — 회차 ID가 와도 null", () => {
    const links = seatLinks(
      {
        columns: [
          { name: "movie_title", type: "text" },
          { name: "remaining_seats", type: "integer" },
        ],
        rows: [["별의 항해", 129]],
        screeningIds: [769004021],
      },
      NOW,
    );
    expect(links).toBeNull();
  });
});
