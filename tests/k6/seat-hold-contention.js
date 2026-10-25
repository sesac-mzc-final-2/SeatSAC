// 실시간 좌석 데모 ①: 같은 좌석 100명 동시 선점 → 성공 정확히 1명, 선점 p95 300ms 이하
//
// 필요한 것
//   K6_TOKENS     서로 다른 Cognito 테스트 계정 토큰 100개 (쉼표 구분)
//                 — 회차당 선점 1건 제한 때문에 토큰이 겹치면 좌석 경쟁이 아니라
//                   사용자 제한(ACTIVE_HOLD_EXISTS)을 재게 된다
//   SCREENING_ID  아직 아무도 잡지 않은 회차
//   SEAT_ID       그 회차의 활성 좌석 하나
//
// 다시 돌릴 때는 좌석이 비어 있어야 한다. 선점 TTL이 300초라 그만큼 기다리거나
// DELETE /bookings/{id}/hold 로 풀고 시작한다.
import http from "k6/http";
import { check } from "k6";
import { Counter, Trend } from "k6/metrics";
import { BASE, authHeaders, reasonOf, requireDistinctTokens, tokenForVu } from "./lib/common.js";

const VUS = Number(__ENV.VUS || 100);

const held = new Counter("hold_success");
const seatTaken = new Counter("hold_seat_taken");
const activeHold = new Counter("hold_active_hold");
const other = new Counter("hold_other");
const holdLatency = new Trend("hold_latency", true);

export const options = {
  scenarios: {
    contention: { executor: "per-vu-iterations", vus: VUS, iterations: 1, maxDuration: "1m" },
  },
  thresholds: {
    // 데모 조건: 성공 정확히 1명 · p95 300ms 이하
    hold_success: ["count==1"],
    hold_latency: ["p(95)<300"],
    // 같은 사용자가 겹쳐 생기는 409는 이 측정에서 0이어야 한다(토큰이 부족한 상태)
    hold_active_hold: ["count==0"],
    hold_other: ["count==0"],
  },
};

export function setup() {
  requireDistinctTokens(VUS);
  return { screeningId: Number(__ENV.SCREENING_ID), seatId: Number(__ENV.SEAT_ID) };
}

export default function (data) {
  const res = http.post(
    `${BASE}/seats/hold`,
    JSON.stringify({ screeningId: data.screeningId, seatIds: [data.seatId] }),
    { headers: authHeaders(tokenForVu(__VU)), tags: { name: "POST /seats/hold" } }
  );
  holdLatency.add(res.timings.duration);

  if (res.status === 201) {
    held.add(1);
  } else if (res.status === 409 && reasonOf(res) === "HELD") {
    seatTaken.add(1);
  } else if (res.status === 409) {
    // ACTIVE_HOLD_EXISTS 등 — 좌석 경쟁이 아닌 이유로 막힌 경우
    activeHold.add(1);
  } else {
    other.add(1);
  }

  check(res, {
    "201 또는 409 (5xx 없음)": (r) => r.status === 201 || r.status === 409,
  });
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const lines = [
    "",
    "같은 좌석 동시 선점 결과",
    `  성공(201)            ${count("hold_success")}   ← 정확히 1이어야 한다`,
    `  좌석 선점됨(409 HELD) ${count("hold_seat_taken")}`,
    `  사용자 제한(409 기타)  ${count("hold_active_hold")}   ← 0이어야 한다(토큰 부족 신호)`,
    `  그 밖                ${count("hold_other")}   ← 0이어야 한다`,
    "",
  ];
  return { stdout: lines.join("\n") };
}
