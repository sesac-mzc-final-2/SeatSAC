/**
 * JWT 본문(payload)을 **검증 없이** 읽는다. 화면 표시용(예: `cognito:groups`로 관리자 메뉴)만 —
 * 서명·만료는 서버가 본다. 형식이 틀리면 null
 */
export function decodeJwtPayload(token: string | null | undefined): Record<string, unknown> | null {
  const part = token?.split(".")[1];
  if (!part) return null;
  try {
    const base64 = part.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(part.length / 4) * 4, "=");
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    const json: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return json && typeof json === "object" && !Array.isArray(json) ? (json as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** Cognito `cognito:groups` 클레임 — 없거나 배열이 아니면 빈 배열, 문자열만 남긴다 */
export function cognitoGroupsOf(payload: Record<string, unknown> | null | undefined): string[] {
  const groups = payload?.["cognito:groups"];
  return Array.isArray(groups) ? groups.filter((g): g is string => typeof g === "string") : [];
}
