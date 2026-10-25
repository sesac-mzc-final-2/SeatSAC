// k6 스크립트 공통 — 토큰·UUID·오류 읽기. 외부 jslib을 import하지 않는다
// (부하 테스트가 외부 의존으로 흔들리지 않게).

/** 클러스터 안에서는 gateway Service를 직접 부른다 → /api 접두사가 없다 */
export const BASE = __ENV.BASE_URL || "http://gateway:8080";

/**
 * Cognito 테스트 계정 토큰. 쉼표로 구분해 K6_TOKENS로 넘긴다.
 * 인증 API 요청 수 제한 때문에 k6 안에서 로그인하지 않고 미리 발급해 둔다.
 */
export const TOKENS = (__ENV.K6_TOKENS || "").split(",").filter((t) => t.length > 0);

/**
 * VU마다 다른 사용자를 쓴다. 토큰이 VU보다 적으면 같은 사용자가 겹치는데, booking은
 * 회차당 선점 1건만 허용하므로(user-hold) 두 번째 요청이 409 ACTIVE_HOLD_EXISTS가 된다 —
 * 좌석 경쟁(SEAT_UNAVAILABLE)을 재려는 측정이 사용자 제한에 가려진다.
 */
export function tokenForVu(vu) {
  if (TOKENS.length === 0) {
    throw new Error("K6_TOKENS가 비었다. Cognito 테스트 계정 토큰을 쉼표로 넘겨라");
  }
  return TOKENS[(vu - 1) % TOKENS.length];
}

export function requireDistinctTokens(vus) {
  if (TOKENS.length < vus) {
    throw new Error(
      `토큰이 ${TOKENS.length}개뿐이다. 같은 좌석 경쟁을 재려면 VU(${vus})만큼 서로 다른 사용자가 필요하다 ` +
        "(회차당 선점 1건 제한 때문에 같은 사용자의 두 번째 요청은 ACTIVE_HOLD_EXISTS가 된다)"
    );
  }
}

/** UUID v4 모양 문자열. Idempotency-Key로 쓴다 */
export function uuid() {
  const hex = "0123456789abcdef";
  let out = "";
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) {
      out += "-";
    } else if (i === 14) {
      out += "4";
    } else if (i === 19) {
      out += hex[8 + ((Math.random() * 4) | 0)];
    } else {
      out += hex[(Math.random() * 16) | 0];
    }
  }
  return out;
}

export function authHeaders(token) {
  return {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
    "Idempotency-Key": uuid(),
  };
}

/** 오류 응답의 세분 이름 — details[].reason, 없으면 code (명세 2장) */
export function reasonOf(res) {
  try {
    const body = res.json();
    return (body.details && body.details.length > 0 && body.details[0].reason) || body.code || "";
  } catch (e) {
    return "";
  }
}
