import { useEffect, useId, useRef, type ReactNode } from "react";
import { Button } from "../button/Button";
import { Icon } from "../icon/Icon";
import styles from "./ConfirmDialog.module.css";

interface Props {
  open: boolean;
  title: string;
  description?: ReactNode;
  confirmLabel: string;
  cancelLabel?: string;
  /** 요청 중: 버튼 비활성, 닫기 막음 */
  busy?: boolean;
  busyLabel?: string;
  onConfirm: () => void;
  onClose: () => void;
}

/**
 * 확인 대화상자 (예매 취소 등). 네이티브 <dialog> 의 showModal 로 포커스 트랩·Esc 를 맡긴다.
 * 바깥(backdrop)을 누르면 닫히고, busy 동안은 Esc·바깥 클릭을 무시한다
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel = "돌아가기",
  busy = false,
  busyLabel,
  onConfirm,
  onClose,
}: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className={styles.dialog}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onCancel={(event) => {
        // Esc: busy 동안은 그대로 두고, 아니면 부모 상태로 닫는다 (dialog 가 스스로 닫히면 open 과 어긋난다)
        event.preventDefault();
        if (!busy) onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget && !busy) onClose();
      }}
    >
      <div className={styles.content}>
        <button type="button" className={styles.close} aria-label="닫기" onClick={onClose} disabled={busy}>
          <Icon name="x" size={18} />
        </button>
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        {description && (
          <p id={descriptionId} className={styles.description}>
            {description}
          </p>
        )}
        <div className={styles.actions}>
          <Button onClick={onClose} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button variant="primary" onClick={onConfirm} disabled={busy} aria-busy={busy}>
            {busy ? (busyLabel ?? confirmLabel) : confirmLabel}
          </Button>
        </div>
      </div>
    </dialog>
  );
}
