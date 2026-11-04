import type { Preferences, TimeSlot } from "@/entities/user";

/** 선호 극장은 최대 3곳 (USR-05, rank 1~3) */
export const MAX_PREFERRED_THEATERS = 3;

/** 화면에서 편집하는 모양: 극장은 순서가 곧 순위, 시간대는 하나, 장르는 여러 개 */
export interface PreferencesDraft {
  theaterIds: number[];
  timeSlot: TimeSlot | null;
  genres: string[];
}

export function toDraft(preferences: Preferences): PreferencesDraft {
  return {
    theaterIds: [...preferences.preferredTheaters].sort((a, b) => a.rank - b.rank).map((t) => t.theaterId),
    timeSlot: preferences.preferredTimeSlot,
    genres: [...preferences.preferredGenres],
  };
}

/** 저장 요청 본문: 순서를 rank 1~N 으로 */
export function toPreferences(draft: PreferencesDraft): Preferences {
  return {
    preferredTheaters: draft.theaterIds.map((theaterId, index) => ({ rank: index + 1, theaterId })),
    preferredTimeSlot: draft.timeSlot,
    preferredGenres: [...draft.genres],
  };
}

/** 선택되어 있으면 빼고, 아니면 맨 뒤(가장 낮은 순위)에 넣는다. 3곳이 차 있으면 그대로 돌려준다 */
export function toggleTheater(draft: PreferencesDraft, theaterId: number): PreferencesDraft {
  if (draft.theaterIds.includes(theaterId)) {
    return { ...draft, theaterIds: draft.theaterIds.filter((id) => id !== theaterId) };
  }
  if (draft.theaterIds.length >= MAX_PREFERRED_THEATERS) return draft;
  return { ...draft, theaterIds: [...draft.theaterIds, theaterId] };
}

export const isTheaterLimitReached = (draft: PreferencesDraft): boolean =>
  draft.theaterIds.length >= MAX_PREFERRED_THEATERS;

/** 순위 이동 (드래그·위/아래 버튼). 범위를 벗어나거나 같은 자리면 그대로 */
export function moveTheater(draft: PreferencesDraft, from: number, to: number): PreferencesDraft {
  const { theaterIds } = draft;
  if (from === to || from < 0 || to < 0 || from >= theaterIds.length || to >= theaterIds.length) return draft;
  const next = [...theaterIds];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return { ...draft, theaterIds: next };
}

/** 시간대는 하나만: 이미 선택된 칩을 다시 누르면 해제 */
export function setTimeSlot(draft: PreferencesDraft, slot: TimeSlot): PreferencesDraft {
  return { ...draft, timeSlot: draft.timeSlot === slot ? null : slot };
}

export function toggleGenre(draft: PreferencesDraft, genre: string): PreferencesDraft {
  return {
    ...draft,
    genres: draft.genres.includes(genre) ? draft.genres.filter((g) => g !== genre) : [...draft.genres, genre],
  };
}

/** 저장 버튼 활성 여부에 쓴다. 장르는 순서를 무시하고 비교한다 */
export function isSameDraft(a: PreferencesDraft, b: PreferencesDraft): boolean {
  if (a.timeSlot !== b.timeSlot) return false;
  if (a.theaterIds.length !== b.theaterIds.length || a.theaterIds.some((id, i) => id !== b.theaterIds[i])) return false;
  if (a.genres.length !== b.genres.length) return false;
  const genres = new Set(b.genres);
  return a.genres.every((g) => genres.has(g));
}
