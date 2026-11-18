import { Chip } from "@/shared/ui";
import styles from "./ChoicePicker.module.css";

export interface PickerOption {
  key: string;
  label: string;
}

interface Props {
  prompt: string;
  /** null 이면 목록을 불러오는 중 */
  options: PickerOption[] | null;
  failed?: boolean;
  disabled: boolean;
  onPick: (key: string) => void;
  onCancel: () => void;
}

/** 추천 질문의 빈칸(극장·영화·날짜·등급)을 고르는 말풍선. 고르면 질문을 보낸다 */
export function ChoicePicker({ prompt, options, failed, disabled, onPick, onCancel }: Props) {
  return (
    <div className={styles.picker} role="group" aria-label={prompt}>
      <p className={styles.prompt}>{prompt}</p>
      {failed ? (
        <p className={styles.note}>목록을 불러오지 못했어요. 입력창에 직접 물어봐 주세요.</p>
      ) : options === null ? (
        <p className={styles.note}>불러오는 중…</p>
      ) : (
        <div className={styles.options}>
          {options.map((option) => (
            <Chip key={option.key} size="sm" disabled={disabled} onClick={() => onPick(option.key)}>
              {option.label}
            </Chip>
          ))}
        </div>
      )}
      <button type="button" className={styles.cancel} onClick={onCancel}>
        닫기
      </button>
    </div>
  );
}
