import type { SessionUser } from "../model/types";

/** Cognito 그룹 이름 (conventions 9장). `ADMIN` = 관리자 `/ops`만 */
export const ADMIN_GROUP = "ADMIN";

/**
 * 관리자 메뉴를 보여 줄지. 토큰을 화면에서 해석한 값이라 **표시용**이다 — 실제 허용은 ops-api가
 * 토큰을 검증해 정한다(아니면 403 `FORBIDDEN`, ADR-119)
 */
export const isAdminUser = (user: SessionUser | null | undefined): boolean =>
  user?.groups.includes(ADMIN_GROUP) ?? false;
