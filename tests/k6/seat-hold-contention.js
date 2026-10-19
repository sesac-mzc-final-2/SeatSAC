import http from "k6/http";
import { check } from "k6";

// 같은 좌석 100명 동시 선점 → 201 은 정확히 1건, 나머지는 409 SEAT_UNAVAILABLE
export const options = { scenarios: { contention: { executor: "per-vu-iterations", vus: 100, iterations: 1 } } };
const BASE = __ENV.BASE_URL || "http://gateway:8080";   // 클러스터 안에서는 /api 접두사 없이
const TOKENS = (__ENV.K6_TOKENS || "").split(",");

export default function () {
  const token = TOKENS[__VU % TOKENS.length];
  const res = http.post(`${BASE}/seats/hold`,
    JSON.stringify({ screeningId: Number(__ENV.SCREENING_ID), seatIds: [Number(__ENV.SEAT_ID)] }),
    { headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}`, "Idempotency-Key": crypto.randomUUID() } });
  check(res, { "201 or 409": (r) => r.status === 201 || r.status === 409 });
}
