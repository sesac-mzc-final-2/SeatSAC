import { useState } from "react";
import type { Theater } from "@/entities/theater";
import { TIME_SLOTS, type Preferences } from "@/entities/user";
import { Button, Chip, Icon, useToast } from "@/shared/ui";
import { GENRE_OPTIONS } from "../config/genres";
import {
  MAX_PREFERRED_THEATERS,
  isSameDraft,
  isTheaterLimitReached,
  moveTheater,
  setTimeSlot,
  toDraft,
  toPreferences,
  toggleGenre,
  toggleTheater,
} from "../model/preferences-draft";
import { useSavePreferences } from "../model/use-save-preferences";
import { TheaterRankList } from "./TheaterRankList";
import styles from "./PreferencesEditor.module.css";

interface Props {
  /** 고를 수 있는 극장 (배치 있는 4곳) */
  theaters: Theater[];
  /** 저장돼 있던 값. 바뀌면 부모가 key 를 바꿔 다시 그린다 */
  initial: Preferences;
}

/** USR-05 선호 설정 편집 (W8 탭 2): 극장 최대 3곳(순위) · 시간대 하나 · 장르 여러 개 · 저장 */
export function PreferencesEditor({ theaters, initial }: Props) {
  const toast = useToast();
  const [draft, setDraft] = useState(() => toDraft(initial));
  const [savedDraft, setSavedDraft] = useState(draft);
  const save = useSavePreferences({ onSuccess: (saved) => setSavedDraft(toDraft(saved)) });

  const theaterName = (theaterId: number) =>
    theaters.find((t) => t.theaterId === theaterId)?.name ?? `극장 ${theaterId}`;
  const ranked = draft.theaterIds.map((theaterId) => ({ theaterId, name: theaterName(theaterId) }));
  // 저장된 장르 중 기본 목록에 없는 것도 보여 준다 (지울 수 있게)
  const genreOptions = [...GENRE_OPTIONS, ...draft.genres.filter((g) => !GENRE_OPTIONS.includes(g))];
  const dirty = !isSameDraft(draft, savedDraft);

  const onToggleTheater = (theaterId: number) => {
    if (!draft.theaterIds.includes(theaterId) && isTheaterLimitReached(draft)) {
      toast.show(`선호 극장은 최대 ${MAX_PREFERRED_THEATERS}곳까지 고를 수 있어요.`);
      return;
    }
    setDraft((d) => toggleTheater(d, theaterId));
  };

  return (
    <form
      className={styles.layout}
      aria-label="선호 설정"
      onSubmit={(event) => {
        event.preventDefault();
        if (dirty && !save.isPending) save.mutate(toPreferences(draft));
      }}
    >
      <section className={`panel ${styles.section}`} aria-labelledby="pref-theaters">
        <div className={styles.heading}>
          <div>
            <p className="eyebrow">취향에 맞는 극장</p>
            <h2 id="pref-theaters">선호 극장</h2>
          </div>
          <span className="micro">최대 {MAX_PREFERRED_THEATERS}곳 · 끌어서 순서를 바꿔요</span>
        </div>
        <div className={styles.chips} role="group" aria-label="선호 극장 선택">
          {theaters.map((theater) => (
            <Chip
              key={theater.theaterId}
              selected={draft.theaterIds.includes(theater.theaterId)}
              onClick={() => onToggleTheater(theater.theaterId)}
              title={theater.region ?? undefined}
            >
              {theater.name}
            </Chip>
          ))}
        </div>
        <TheaterRankList items={ranked} onMove={(from, to) => setDraft((d) => moveTheater(d, from, to))} />
      </section>

      <section className={`panel ${styles.section}`} aria-labelledby="pref-time">
        <div className={styles.heading}>
          <div>
            <p className="eyebrow">나만의 관람 시간</p>
            <h2 id="pref-time">선호 시간대</h2>
          </div>
          <span className="micro">하나만 골라요</span>
        </div>
        <div className={styles.chips} role="group" aria-label="선호 시간대 선택">
          {TIME_SLOTS.map((slot) => (
            <Chip
              key={slot.value}
              selected={draft.timeSlot === slot.value}
              onClick={() => setDraft((d) => setTimeSlot(d, slot.value))}
            >
              {slot.label}
            </Chip>
          ))}
        </div>

        <div className={`${styles.heading} ${styles.genreHeading}`}>
          <div>
            <p className="eyebrow">좋아하는 이야기</p>
            <h2 id="pref-genres">선호 장르</h2>
          </div>
        </div>
        <div className={styles.chips} role="group" aria-labelledby="pref-genres">
          {genreOptions.map((genre) => (
            <Chip key={genre} selected={draft.genres.includes(genre)} onClick={() => setDraft((d) => toggleGenre(d, genre))}>
              {genre}
            </Chip>
          ))}
        </div>

        <div className={styles.saveRow}>
          <Button type="submit" variant="primary" disabled={!dirty || save.isPending} aria-busy={save.isPending}>
            <Icon name="save" />
            {save.isPending ? "저장하는 중…" : "선호 설정 저장"}
          </Button>
          <span className="micro" role="status">
            {dirty ? "저장하지 않은 변경이 있어요" : "저장된 상태예요"}
          </span>
        </div>
      </section>
    </form>
  );
}
