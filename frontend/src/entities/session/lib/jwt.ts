import type { UserRole } from "../model/types";

/** 서명 검증 없이 payload만 읽는다 (화면 표시용. 권한 판단은 Gateway가 한다) */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const part = token.split(".")[1];
  if (!part) return null;
  try {
    const base64 = part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "=");
    const binary = atob(base64);
    const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
    const json = new TextDecoder().decode(bytes);
    const parsed: unknown = JSON.parse(json);
    return parsed && typeof parsed === "object" ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Cognito Access Token의 `cognito:groups`에 ADMIN이 있으면 ADMIN */
export function roleFromAccessToken(token: string | null | undefined): UserRole {
  const payload = token ? decodeJwtPayload(token) : null;
  const groups = payload?.["cognito:groups"];
  return Array.isArray(groups) && groups.includes("ADMIN") ? "ADMIN" : "USER";
}
