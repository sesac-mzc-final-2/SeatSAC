import type { Profile } from "../model/types";

/** VIP 인지 (없거나 모르는 값은 NORMAL 로 본다). 화면 안내용 — 쿠폰 발급 판정은 서버(claim)가 한다 */
export const isVip = (profile: Pick<Profile, "grade"> | null | undefined): boolean => profile?.grade === "VIP";
