// 조회 경로 부하: 영화·상영시간·좌석 현황. 캐시 적중 p95 100ms (명세서 10장)
//
// 인증이 필요 없는 경로만 쓴다 — 토큰 없이 돌 수 있어 데모 전 점검에 가볍다.
//   SCREENING_IDS  쉼표로 구분한 회차 ID 목록 (여러 개여야 캐시 적중률이 의미가 있다)
//   THEATER_ID     /screenings 조회에 쓸 극장 ID — 계약에서 date와 함께 필수다
//   THEATER_DATE   /screenings 조회에 쓸 영업일 (기본: 오늘, KST)
//   VUS            유지 구간 VU 수 (기본 20). 5.1.6 독립 확장 시연은 50~80
//   DURATION       유지 구간 길이 (기본 1m, k6 형식 30s·5m·1m30s). HPA가 늘어나는 걸 보려면 5m 이상
//                  — 전체 실행 = 올리기 30초 + DURATION + 내리기 15초
//   TARGET         all(기본) · catalog. catalog = 5.1.6 독립 확장 시연 — catalog만 부르고 booking을 부르는 요청은 뺀다
//                  (좌석 현황은 booking, /screenings는 catalog가 booking seat-counts를 부른다). 그래서 booking HPA는 그대로이고
//                  catalog만 1→N으로 는다. SCREENING_IDS·THEATER_ID가 필요 없다(영화 ID는 setup에서 목록으로 받는다)
import http from "k6/http";
import { check, group } from "k6";
import { Trend } from "k6/metrics";
import { BASE, intEnv, intListEnv } from "./lib/common.js";

const DATE = __ENV.THEATER_DATE || new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 10);

// options는 init 단계에서 정해지므로 setup()이 아니라 여기서 확인한다 — 잘못된 값이면 부하 전에 멈춘다
const VUS = positiveInt(__ENV.VUS, 20, "VUS");
const DURATION = k6Duration(__ENV.DURATION, "1m", "DURATION");
const TARGET = (__ENV.TARGET || "all").trim();
if (TARGET !== "all" && TARGET !== "catalog") throw new Error(`TARGET은 all 또는 catalog: "${TARGET}"`);
const CATALOG_ONLY = TARGET === "catalog";

function positiveInt(raw, fallback, name) {
  const value = (raw || "").trim();
  if (value === "") return fallback;
  if (!/^[1-9]\d*$/.test(value)) throw new Error(`${name}는 양의 정수여야 한다: "${value}"`);
  return Number(value);
}

function k6Duration(raw, fallback, name) {
  const value = (raw || "").trim();
  if (value === "") return fallback;
  if (!/^(\d+h)?(\d+m)?(\d+s)?$/.test(value)) throw new Error(`${name}는 30s·5m·1m30s 형식이어야 한다: "${value}"`);
  return value;
}

const movies = new Trend("browse_movies", true);
const screenings = new Trend("browse_screenings", true);
const seats = new Trend("browse_seats", true);
const movieDetail = new Trend("browse_movie_detail", true);
const theaters = new Trend("browse_theaters", true);

// 캐시 적중 목표. 좌석 현황(booking)은 RDS 집계가 있어 조금 느슨하게 둔다.
// TARGET=catalog는 부르지 않는 경로의 임계값을 빼고 catalog 경로만 본다
const LATENCY_THRESHOLDS = CATALOG_ONLY
  ? { browse_movies: ["p(95)<100"], browse_movie_detail: ["p(95)<100"], browse_theaters: ["p(95)<100"] }
  : { browse_movies: ["p(95)<100"], browse_screenings: ["p(95)<100"], browse_seats: ["p(95)<200"] };

export const options = {
  scenarios: {
    browse: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "30s", target: VUS },
        { duration: DURATION, target: VUS },
        { duration: "15s", target: 0 },
      ],
    },
  },
  thresholds: {
    ...LATENCY_THRESHOLDS,
    http_req_failed: ["rate==0"],
    // check 실패는 그 자체로 run을 실패시키지 않는다. 응답 모양(seq·seats)이 깨지면
    // 200·p95는 멀쩡해도 측정이 무의미하므로 임계값으로 묶어 둔다
    checks: ["rate==1"],
  },
};

export function setup() {
  if (CATALOG_ONLY) {
    // 영화 상세를 돌려 가며 부르려고 목록에서 ID를 받는다(시드가 바뀌어도 그대로 돈다)
    const res = http.get(`${BASE}/movies?page=0&size=20`, { tags: { name: "GET /movies (setup)" } });
    const ids = res.status === 200 ? (res.json("content") || []).map((m) => m.movieId) : [];
    if (ids.length === 0) throw new Error(`영화 목록이 비었거나 실패했다(status ${res.status}) — 시드·catalog를 먼저 확인`);
    return { movieIds: ids };
  }
  // CAT-03은 date·theaterId 둘 다 required이고 theaterId는 integer다
  // (contracts/openapi/catalog.openapi.yaml). 빼거나 정수가 아니면 400 VALIDATION_FAILED여서
  // 캐시 적중률을 재지 못하고 임계값만 깨진다 → 부하를 돌리기 전에 멈춘다
  return {
    screeningIds: intListEnv("SCREENING_IDS", "시드가 적재된 회차 ID를 쉼표로 넘겨라"),
    theaterId: intEnv("THEATER_ID", "/screenings는 date와 theaterId가 모두 필수다"),
  };
}

export default function (data) {
  if (CATALOG_ONLY) {
    catalogOnly(data);
    return;
  }
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

/** TARGET=catalog: catalog가 혼자 답하는 조회만(booking 호출 없음). 캐시 적중이라 요청당 CPU는 작지만 VU를 올리면 catalog CPU가 70%를 넘어 HPA가 는다 */
function catalogOnly(data) {
  group("catalog", () => {
    const list = http.get(`${BASE}/movies?page=0&size=20`, { tags: { name: "GET /movies" } });
    movies.add(list.timings.duration);
    check(list, { "영화 목록 200": (r) => r.status === 200 });

    const id = data.movieIds[__ITER % data.movieIds.length];
    const detail = http.get(`${BASE}/movies/${id}`, { tags: { name: "GET /movies/{id}" } });
    movieDetail.add(detail.timings.duration);
    check(detail, { "영화 상세 200": (r) => r.status === 200 });

    const all = http.get(`${BASE}/theaters`, { tags: { name: "GET /theaters" } });
    theaters.add(all.timings.duration);
    check(all, { "극장 목록 200": (r) => r.status === 200 });
  });
}
