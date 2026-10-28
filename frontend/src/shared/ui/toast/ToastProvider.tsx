import { useCallback, useMemo, useRef, useState, type ReactNode } from "react";
import { ToastContext, type ToastApi, type ToastTone, type ToastAction } from "./toast-context";
import styles from "./Toast.module.css";

interface ToastItem {
  id: number;
  message: string;
  tone: ToastTone;
  action?: ToastAction;
}

/** 공통 토스트 (503 SERVICE_UNAVAILABLE 등). app 계층에서 한 번 감싼다 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const seq = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const show = useCallback<ToastApi["show"]>(
    (message, options = {}) => {
      const id = ++seq.current;
      setToasts((current) => [...current, { id, message, tone: options.tone ?? "info", action: options.action }]);
      window.setTimeout(() => dismiss(id), options.durationMs ?? 5000);
    },
    [dismiss],
  );

  const api = useMemo<ToastApi>(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className={styles.viewport} role="status" aria-live="polite">
        {toasts.map((toast) => (
          <div key={toast.id} className={styles.toast} data-tone={toast.tone}>
            <span className={styles.message}>{toast.message}</span>
            {toast.action && (
              <button
                type="button"
                className={styles.action}
                onClick={() => {
                  toast.action?.onClick();
                  dismiss(toast.id);
                }}
              >
                {toast.action.label}
              </button>
            )}
            <button type="button" className={styles.close} aria-label="닫기" onClick={() => dismiss(toast.id)}>
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
