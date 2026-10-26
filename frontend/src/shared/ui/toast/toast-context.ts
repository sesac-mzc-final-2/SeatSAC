import { createContext, useContext } from "react";

export type ToastTone = "info" | "error";

export interface ToastAction {
  label: string;
  onClick: () => void;
}

export interface ToastOptions {
  tone?: ToastTone;
  action?: ToastAction;
  durationMs?: number;
}

export interface ToastApi {
  show: (message: string, options?: ToastOptions) => void;
}

export const ToastContext = createContext<ToastApi>({ show: () => {} });

export const useToast = (): ToastApi => useContext(ToastContext);
