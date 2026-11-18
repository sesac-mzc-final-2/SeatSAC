import type { ChatPreset } from "@/entities/ai-chat";
import type { MovieSummary } from "@/entities/movie";
import type { Theater } from "@/entities/theater";
import { routes } from "@/shared/config";
import { addDays, dayOfWeekKo } from "@/shared/lib";

/**
 * 추천 질문(프리셋) 버튼을 눌렀을 때 할 일.
 * 예전에는 example 문장("오늘 20시 이후…")을 그대로 보내 지금 시각·원하는 극장과 상관없는 답이 나왔다.
 * - 빈칸이 없는 질문 → 바로 보낸다
 * - 숫자 빈칸(시각·개수) → 입력창에 문장을 채우고 그 숫자를 선택해 둔다(사용자가 고쳐서 보낸다)
 * - 극장·영화·날짜·등급 → 대화창에 고르는 버튼을 띄운다. 고르면 보낸다
 *
 * 보내는 문장은 ai `presets.yaml` 의 phrasings 와 같은 말투다 — 처음 누른 사람도 캐시로 바로 답한다.
 */

export interface ChatLink {
  label: string;
  href: string;
}

export interface PresetChoice {
  key: string;
  label: string;
  /** 고르면 보낼 질문 */
  text: string;
  /** 답 아래에 붙일 바로가기 */
  link?: ChatLink;
}

export type PickKind = "theater" | "movie" | "date" | "grade";

export type PresetAction =
  { kind: "send"; text: string } | { kind: "fill"; text: string; select: string } | { kind: "pick"; pick: PickKind };

export const PICK_PROMPTS: Record<PickKind | "more", string> = {
  theater: "어느 극장의 편성표를 볼까요?",
  movie: "어떤 영화가 궁금하세요?",
  date: "어느 날 상영하는 영화를 볼까요?",
  grade: "어떤 관람 등급을 볼까요?",
  more: "무엇을 물어볼까요?",
};

const SEND: Record<string, string> = {
  almost_sold_out_today: "오늘 곧 매진되는 회차 있어?",
  screen_type_today: "오늘 상영관 종류별로 회차 몇 개야?",
  late_night_today: "오늘 심야 영화 뭐 있어?",
  theater_counts_today: "오늘 극장별로 남은 회차 몇 개야?",
};

const PICK: Record<string, PickKind> = {
  theater_schedule: "theater",
  movie_theaters: "movie",
  movie_next_screening: "movie",
  movie_runtime: "movie",
  movies_on_date: "date",
  movies_by_view_grade: "grade",
};

const kstHourFormatter = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Asia/Seoul",
  hour: "2-digit",
  hourCycle: "h23",
});

export const kstHour = (now: Date = new Date()): number => Number(kstHourFormatter.format(now));

export function presetAction(preset: ChatPreset, hour: number): PresetAction {
  if (preset.presetId === "today_after_time") {
    const next = String(Math.min(23, hour + 1));
    return {
      kind: "fill",
      text: `오늘 ${next}시 이후에 시작하는 영화 알려줘`,
      select: next,
    };
  }
  if (preset.presetId === "most_remaining_today") {
    return {
      kind: "fill",
      text: "오늘 자리 많이 남은 회차 5개",
      select: "5",
    };
  }
  const pick = PICK[preset.presetId];
  if (pick) return { kind: "pick", pick };
  // 모르는 프리셋(나중에 추가된 것)은 예전처럼 example 을 보낸다
  return { kind: "send", text: SEND[preset.presetId] ?? preset.example };
}

export function theaterChoices(theaters: Theater[], today: string): PresetChoice[] {
  return theaters.map((theater) => ({
    key: String(theater.theaterId),
    label: theater.name,
    text: `오늘 ${theater.name} 편성표 보여줘`,
    link: {
      label: `${theater.name} 상영시간표 바로가기`,
      href: routes.showtimes({
        theaterId: theater.theaterId,
        date: today,
      }),
    },
  }));
}

const MOVIE_TEXT: Record<string, (title: string) => string> = {
  movie_theaters: (title) => `${title} 어디서 몇 시에 해?`,
  movie_next_screening: (title) => `${title} 다음 회차 언제야?`,
  movie_runtime: (title) => `${title} 몇 분짜리야?`,
};

export function movieChoices(presetId: string, movies: MovieSummary[]): PresetChoice[] {
  const text = MOVIE_TEXT[presetId] ?? MOVIE_TEXT.movie_theaters;
  return movies.map((movie) => ({
    key: String(movie.movieId),
    label: movie.title,
    text: text(movie.title),
    link:
      presetId === "movie_runtime"
        ? { label: "영화 정보 보기", href: routes.movie(movie.movieId) }
        : {
            label: `${movie.title} 상영시간표 보기`,
            href: routes.showtimes({ movieId: movie.movieId }),
          },
  }));
}

const DAY_WORDS = ["오늘", "내일", "모레"];

/** 오늘부터 4일 (상영 정보가 며칠치만 있다) */
export function dateChoices(today: string): PresetChoice[] {
  return [0, 1, 2, 3].map((offset) => {
    const date = addDays(today, offset);
    const word = DAY_WORDS[offset] ?? `${dayOfWeekKo(date)}요일`;
    const text = offset < DAY_WORDS.length ? `${word} 상영하는 영화 뭐 있어?` : `${word}에 상영하는 영화 뭐 있어?`;
    return {
      key: date,
      label: word,
      text,
      link: {
        label: "그날 상영시간표 보기",
        href: routes.showtimes({ date }),
      },
    };
  });
}

const GRADES = ["전체 관람가", "12세 관람가", "15세 관람가", "청소년 관람불가"];

export function gradeChoices(): PresetChoice[] {
  return GRADES.map((label) => ({
    key: label,
    label,
    text: `${label} 영화 알려줘`,
  }));
}
