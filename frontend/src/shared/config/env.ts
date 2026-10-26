/** 빌드 시 주입되는 공개 값(VITE_). 이름은 docs/service-runtime-contract.md 3장 frontend 표와 같다. */
const metaEnv = import.meta.env;

export const env = {
  apiBaseUrl: metaEnv.VITE_API_BASE_URL ?? "/api",
  cognito: {
    authority: metaEnv.VITE_COGNITO_AUTHORITY ?? "",
    clientId: metaEnv.VITE_COGNITO_CLIENT_ID ?? "",
    domain: metaEnv.VITE_COGNITO_DOMAIN ?? "",
    redirectUri: metaEnv.VITE_COGNITO_REDIRECT_URI ?? `${window.location.origin}/callback`,
  },
} as const;

/** Cognito 값이 없으면(로컬 기본) 로그인 기능을 끈 채로 조회 화면만 동작한다 */
export const isCognitoConfigured = env.cognito.authority !== "" && env.cognito.clientId !== "";
