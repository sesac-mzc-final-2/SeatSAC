// 조회 경로 부하: 영화·상영시간·좌석 현황. 캐시 적중 p95 100ms (명세서 10장)
//
// 인증이 필요 없는 경로만 쓴다 — 토큰 없이 돌 수 있어 데모 전 점검에 가볍다.
//   SCREENING_IDS  쉼표로 구분한 회차 ID 목록 (여러 개여야 캐시 적중률이 의미가 있다)
//   THEATER_ID     /screenings 조회에 쓸 극장 ID — 계약에서 date와 함께 필수다
//   THEATER_DATE   /screenings 조회에 쓸 영업일 (기본: 오늘, KST)
import http from "k6/http";
import { check, group } from "k6";
import { Trend } from "k6/metrics";
import { BASE, intEnv, intListEnv } from "./lib/common.js";

const DATE = __ENV.THEATER_DATE || new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);

const movies = new Trend("browse_movies", true);
const screenings = new Trend("browse_screenings", true);
const seats = new Trend("browse_seats", true);

export const options = {
  scenarios: {
    browse: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "30s", target: 20 },
        { duration: "1m", target: 20 },
        { duration: "15s", target: 0 },
      ],
    },
  },
  thresholds: {
    // 캐시 적중 목표. 좌석 현황(booking)은 RDS 집계가 있어 조금 느슨하게 둔다
    browse_movies: ["p(95)<100"],
    browse_screenings: ["p(95)<100"],
    browse_seats: ["p(95)<200"],
    http_req_failed: ["rate==0"],
    // check 실패는 그 자체로 run을 실패시키지 않는다. 응답 모양(seq·seats)이 깨지면
    // 200·p95는 멀쩡해도 측정이 무의미하므로 임계값으로 묶어 둔다
    checks: ["rate==1"],
  },
};

export function setup() {
  // CAT-03은 date·theaterId 둘 다 required이고 theaterId는 integer다
  // (contracts/openapi/catalog.openapi.yaml). 빼거나 정수가 아니면 400 VALIDATION_FAILED여서
  // 캐시 적중률을 재지 못하고 임계값만 깨진다 → 부하를 돌리기 전에 멈춘다
  return {
    screeningIds: intListEnv("SCREENING_IDS", "시드가 적재된 회차 ID를 쉼표로 넘겨라"),
    theaterId: intEnv("THEATER_ID", "/screenings는 date와 theaterId가 모두 필수다"),
  };
}

export default function (data) {
  group("catalog", () => {
    const list = http.get(`${BASE}/movies?page=0&size=20`, { tags: { name: "GET /movies" } });
    movies.add(list.timings.duration);
    check(list, { "영화 목록 200": (r) => r.status === 200 });

    const byDate = http.get(`${BASE}/screenings?date=${DATE}&theaterId=${data.theaterId}`, {
      tags: { name: "GET /screenings" },
    });
    screenings.add(byDate.timings.duration);
    check(byDate, { "상영시간 200": (r) => r.status === 200 });
  });

  group("booking", () => {
    const id = data.screeningIds[__ITER % data.screeningIds.length];
    const snapshot = http.get(`${BASE}/screenings/${id}/seats`, {
      tags: { name: "GET /screenings/{id}/seats" },
    });
    seats.add(snapshot.timings.duration);
    check(snapshot, {
      "좌석 현황 200": (r) => r.status === 200,
      "seq·좌석 목록 있음": (r) => {
        try {
          const body = r.json();
          return body.seq !== undefined && Array.isArray(body.seats);
        } catch (e) {
          return false;
        }
      },
    });
  });
}
