import { describe, expect, it } from "vitest";
import {
  answerMovieTitles,
  dateChoices,
  gradeChoices,
  kstHour,
  movieChoices,
  presetAction,
  theaterChoices,
} from "./preset-actions";

const preset = (presetId: string, example = "예시") => ({
  presetId,
  title: presetId,
  example,
});

describe("presetAction", () => {
  it("시각 빈칸은 다음 정시로 채우고 그 숫자를 고칠 수 있게 선택한다", () => {
    expect(presetAction(preset("today_after_time"), 14)).toEqual({
      kind: "fill",
      text: "오늘 15시 이후에 시작하는 영화 알려줘",
      select: "15",
    });
    expect(presetAction(preset("today_after_time"), 23)).toMatchObject({
      text: "오늘 23시 이후에 시작하는 영화 알려줘",
    });
  });

  it("극장·영화·날짜·등급은 고르게 한다", () => {
    expect(presetAction(preset("theater_schedule"), 10)).toEqual({
      kind: "pick",
      pick: "theater",
    });
    expect(presetAction(preset("movie_runtime"), 10)).toEqual({
      kind: "pick",
      pick: "movie",
    });
    expect(presetAction(preset("movies_on_date"), 10)).toEqual({
      kind: "pick",
      pick: "date",
    });
    expect(presetAction(preset("movies_by_view_grade"), 10)).toEqual({
      kind: "pick",
      pick: "grade",
    });
  });

  it("빈칸 없는 질문은 바로 보내고, 모르는 프리셋은 example 을 보낸다", () => {
    expect(presetAction(preset("late_night_today"), 10)).toEqual({
      kind: "send",
      text: "오늘 심야 영화 뭐 있어?",
    });
    expect(presetAction(preset("new_one", "새 예시"), 10)).toEqual({
      kind: "send",
      text: "새 예시",
    });
  });
});

describe("선택지", () => {
  it("극장을 고르면 오늘 편성표를 묻고 상영시간표 바로가기를 붙인다", () => {
    const [choice] = theaterChoices([{ theaterId: 3, name: "영등포", region: "서울", hasLayout: true }], "2026-11-18");
    expect(choice.text).toBe("오늘 영등포 편성표 보여줘");
    expect(choice.link).toEqual({
      label: "영등포 상영시간표 바로가기",
      href: "/showtimes?theaterId=3&date=2026-11-18",
    });
  });

  it("영화 질문 종류마다 문장과 바로가기가 다르다", () => {
    const movie = {
      movieId: 9,
      title: "오디세이",
      titleEn: null,
      viewGrade: "12" as const,
      slotMinutes: 150,
      posterUrl: null,
    };
    expect(movieChoices("movie_theaters", [movie])[0]).toMatchObject({
      text: "오디세이 어디서 몇 시에 해?",
      link: { href: "/showtimes?movieId=9" },
    });
    expect(movieChoices("movie_runtime", [movie])[0]).toMatchObject({
      text: "오디세이 몇 분짜리야?",
      link: { href: "/movies/9" },
    });
  });

  it("날짜는 오늘·내일·모레와 그다음 요일", () => {
    const choices = dateChoices("2026-11-18"); // 수요일
    expect(choices.map((c) => c.label)).toEqual(["오늘", "내일", "모레", "토요일"]);
    expect(choices[3].text).toBe("토요일에 상영하는 영화 뭐 있어?");
    expect(choices[1].link?.href).toBe("/showtimes?date=2026-11-19");
  });

  it("등급", () => {
    expect(gradeChoices().map((c) => c.text)).toContain("청소년 관람불가 영화 알려줘");
    // 버튼 글자는 사이트의 다른 화면(등급 배지)과 같은 표기
    expect(gradeChoices().map((c) => c.label)).toEqual([
      "전체 관람가",
      "12세 이상 관람가",
      "15세 이상 관람가",
      "청소년 관람 불가",
    ]);
  });

  it("KST 시각", () => {
    expect(kstHour(new Date("2026-11-18T03:30:00Z"))).toBe(12);
  });
});

describe("answerMovieTitles", () => {
  const cols = [
    { name: "movie_title", type: "text" as const },
    { name: "starts_at", type: "text" as const },
  ];
  it("두 편 이상이면 나온 순서대로 중복 없이", () => {
    const rows = [
      ["가", "1"],
      ["나", "2"],
      ["가", "3"],
    ];
    expect(answerMovieTitles({ path: "NL2SQL", columns: cols, rows })).toEqual(["가", "나"]);
  });
  it("한 편·영화 칸 없음·내 예매는 띄우지 않는다", () => {
    expect(
      answerMovieTitles({
        path: "NL2SQL",
        columns: cols,
        rows: [
          ["가", "1"],
          ["가", "2"],
        ],
      }),
    ).toEqual([]);
    expect(answerMovieTitles({ path: "NL2SQL", columns: [cols[1]], rows: [["1"], ["2"]] })).toEqual([]);
    expect(
      answerMovieTitles({
        path: "MY_BOOKINGS",
        columns: cols,
        rows: [
          ["가", "1"],
          ["나", "2"],
        ],
      }),
    ).toEqual([]);
  });
});
