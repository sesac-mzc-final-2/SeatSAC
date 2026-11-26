/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_COGNITO_AUTHORITY?: string;
  readonly VITE_COGNITO_CLIENT_ID?: string;
  readonly VITE_COGNITO_DOMAIN?: string;
  readonly VITE_COGNITO_REDIRECT_URI?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

/** vite.config.ts `define` — 개발 서버에서만 값이 있고 빌드에서는 "" (DEV_SESSION) */
declare const __DEV_SESSION__: string;
/** vite.config.ts `define` — 가짜 세션의 Cognito 그룹(쉼표 구분). 개발 서버에서만 값이 있고 빌드에서는 "" (DEV_SESSION_GROUPS) */
declare const __DEV_SESSION_GROUPS__: string;
