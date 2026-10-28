import { createContext, useContext } from "react";
import type { Session } from "./types";

export const unavailableSession: Session = {
  status: "unavailable",
  user: null,
  error: null,
  returnTo: null,
  signIn: () => {},
  signOut: () => {},
};

/** 값은 app 계층의 인증 프로바이더가 넣는다 (react-oidc-context 어댑터) */
export const SessionContext = createContext<Session>(unavailableSession);

export const useSession = (): Session => useContext(SessionContext);
