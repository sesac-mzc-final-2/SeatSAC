// 실시간 좌석 데모 완료 기준 「사용자당 진행 중 선점 1건」(BKG-02, user-hold:{screeningId}:{userId})
//
// 한 사용자가 같은 회차에서
//   ① 좌석 A 선점 → 201
//   ② 좌석 B 선점 → 409 ACTIVE_HOLD_EXISTS (A가 살아 있으므로)
//   ③ A 해제(DELETE /bookings/{id}/hold) → 200
//   ④ 좌석 B 선점 → 201 (해제 뒤에는 다시 잡을 수 있다)
//   ⑤ B 해제 → 200 (좌석을 남기지 않는다)
//
// 필요한 것
//   K6_TOKENS_FILE  토큰 1개 이상 (첫 번째 토큰만 쓴다)
//   SCREENING_ID    그 사용자가 아직 선점하지 않은 회차 — contention과 다른 회차를 쓴다
//                   (contention 승자가 첫 번째 토큰이면 300초 동안 그 회차에 선점이 남아 ①이 409가 된다)
//   SEAT_IDS        그 회차의 비어 있는 활성 좌석 2개, 쉼표 구분 (A,B)
import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";
import { BASE, authHeaders, intEnv, intListEnv, requireDistinctTokens, tokenForVu } from "./lib/common.js";

const unexpected = new Counter("user_hold_unexpected");

export const options = {
  scenarios: { limit: { executor: "per-vu-iterations", vus: 1, iterations: 1, maxDuration: "1m" } },
  thresholds: {
    checks: ["rate==1"],
    user_hold_unexpected: ["count==0"],
  },
};

export function setup() {
  requireDistinctTokens(1);
  const seatIds = intListEnv("SEAT_IDS", "비어 있는 활성 좌석 2개(쉼표)");
  if (seatIds.length !== 2 || seatIds[0] === seatIds[1]) {
    throw new Error(`SEAT_IDS는 서로 다른 좌석 2개여야 한다: ${seatIds.join(",")}`);
  }
  return { screeningId: intEnv("SCREENING_ID", "이 사용자가 아직 선점하지 않은 회차"), seatIds };
}

function hold(token, screeningId, seatId) {
  return http.post(
    `${BASE}/seats/hold`,
    JSON.stringify({ screeningId, seatIds: [seatId] }),
    { headers: authHeaders(token), tags: { name: "POST /seats/hold" } }
  );
}

function release(token, bookingId) {
  return http.del(`${BASE}/bookings/${bookingId}/hold`, null, {
    headers: authHeaders(token), tags: { name: "DELETE /bookings/{id}/hold" },
  });
}

function codeOf(res) {
  try {
    return res.json("code") || "";
  } catch (e) {
    return "";
  }
}

export default function (data) {
  const token = tokenForVu(1);
  const [seatA, seatB] = data.seatIds;

  const first = hold(token, data.screeningId, seatA);
  if (!check(first, { "① 좌석 A 선점 201": (r) => r.status === 201 })) {
    unexpected.add(1);
    console.error(`① ${first.status} ${codeOf(first)} — 이 사용자가 이미 이 회차에 선점이 있거나 좌석이 비어 있지 않다`);
    return;
  }
  const bookingA = first.json("bookingId");

  const second = hold(token, data.screeningId, seatB);
  const limited = check(second, {
    "② 같은 회차 두 번째 선점 409 ACTIVE_HOLD_EXISTS": (r) => r.status === 409 && codeOf(r) === "ACTIVE_HOLD_EXISTS",
  });
  if (!limited) {
    unexpected.add(1);
    console.error(`② ${second.status} ${codeOf(second)} — 사용자당 1건 제한이 걸리지 않았다`);
    if (second.status === 201) {
      release(token, second.json("bookingId"));   // 잘못 잡힌 좌석을 남기지 않는다
    }
  }

  check(release(token, bookingA), { "③ 좌석 A 해제 200": (r) => r.status === 200 });

  const third = hold(token, data.screeningId, seatB);
  if (check(third, { "④ 해제 뒤 좌석 B 선점 201": (r) => r.status === 201 })) {
    check(release(token, third.json("bookingId")), { "⑤ 좌석 B 해제 200": (r) => r.status === 200 });
  } else {
    unexpected.add(1);
    console.error(`④ ${third.status} ${codeOf(third)} — 해제 뒤 user-hold 키가 남았을 수 있다`);
  }
}
