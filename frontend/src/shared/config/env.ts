/** 빌드 시 주입되는 공개 값(VITE_). 이름은 docs/service-runtime-contract.md 3장 frontend 표와 같다. */
const metaEnv = import.meta.env;

export const env = {
  apiBaseUrl: metaEnv.VITE_API_BASE_URL ?? "/api",
  cognito: {
    authority: metaEnv.VITE_COGNITO_AUTHORITY ?? "",
    clientId: metaEnv.VITE_COGNITO_CLIENT_ID ?? "",
    domain: metaEnv.VITE_COGNITO_DOMAIN ?? "",
    // 빈 문자열도 "없음"으로 보고 기본값(현재 출처 + /callback)을 쓴다 (?? 는 빈 문자열에 기본값을 주지 않는다)
    redirectUri: metaEnv.VITE_COGNITO_REDIRECT_URI || `${window.location.origin}/callback`,
  },
} as const;

/**
 * Cognito 값이 없으면(로컬 기본) 로그인 기능을 끈 채로 조회 화면만 동작한다.
 * domain 도 필수: 없으면 Hosted UI 로그아웃을 못 해 Cognito 세션이 남는다.
 */
export const isCognitoConfigured =
  env.cognito.authority !== "" && env.cognito.clientId !== "" && env.cognito.domain !== "";
