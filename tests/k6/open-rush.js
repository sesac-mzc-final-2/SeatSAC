// 4.2.1 오픈런 1,000명 — 인기 회차 예매가 정각에 열리는 순간 (무대인사·개봉일 첫 회차)
//
// 흐름 (기본값, 약 7분 30초)
//   입장   T-65초~T-5초  보는 사람 USERS명이 60초에 걸쳐 좌석 화면에 들어와 SSE를 연결하고 끝까지 유지한다
//   오픈   T0~T0+10초    사는 사람 USERS명이 10초 동안 고르게 첫 선점을 보낸다(좌석 스냅샷 → 1~4석 선점).
//                        409 SEAT_UNAVAILABLE이면 스냅샷을 다시 받아 최대 3번 더 시도, 빈 좌석이 없으면 포기
//   선점 뒤 5~20초 고민한 뒤 결제 70%(그중 5%는 거절 카드 …0000) · 해제 15% · 방치 15%(300초 뒤 만료)
//   정합성 T0+370초       남은 HELD 0석 · RESERVED 증가분 = k6가 확정받은 좌석 수
//
// 왜 사는 사람과 보는 사람을 나누나: SSE 요청은 연결이 끝날 때까지 VU를 붙잡는다. 같은 VU로 선점까지 하면
// SSE를 끊어야 한다. 실제 1,000명은 화면(SSE)을 띄운 채 버튼을 누르므로 SSE 1,000 + HTTP 1,000이 같은 부하다.
//
// SSE는 k6에 클라이언트가 없어 「연결을 유지하는 부하」로만 연다(본문은 버린다, responseType none).
// 반영 지연(1초 이내)은 지금처럼 tools/sse_lag.py로 같은 회차를 열어 잰다(README 「SSE 반영 지연」).
//
// 필요한 것 (run-in-cluster.sh rush가 넣는다)
//   BASE_URL           CloudFront 주소 https://{배포ID}.cloudfront.net/api — 화면과 같은 경로(4.2.1 결정). 클러스터 안 gateway
//                      주소면 멈춘다(ALLOW_INTERNAL=1로 무시)
//   K6_TOKENS_FILES    서로 다른 테스트 계정 토큰 파일들(쉼표, 절대 경로, 줄당 1개) — USERS개 이상. K6_TOKENS_FILE 하나도 된다
//   RUSH_SCREENING_ID  활성 좌석이 전부 비어 있는 회차 — tools/pick_targets.py --rush
//   PAYMENT_MOCK_KEY   모의 결제(CARD) 허용 키 — run-in-cluster.sh가 booking-secrets에서 넣는다(ADR-112)
//   USERS              기본 1000 · MODE=rehearsal 이면 기본 100, 결제 없이 전부 해제하고 60초 뒤 확인(같은 회차를 바로 다시 쓴다)
import http from "k6/http";
import exec from "k6/execution";
import { check, sleep } from "k6";
import { SharedArray } from "k6/data";
import { Counter, Gauge, Trend } from "k6/metrics";
import { BASE, authHeaders, intEnv, reasonOf } from "./lib/common.js";

const REHEARSAL = (__ENV.MODE || "") === "rehearsal";
const USERS = Number(__ENV.USERS || (REHEARSAL ? 100 : 1000));
const ENTRY = Number(__ENV.ENTRY_SECONDS || 60);      // 입장(SSE 연결)에 걸리는 시간
const RUSH = Number(__ENV.RUSH_SECONDS || 10);        // 첫 선점이 퍼지는 시간
const MAX_RETRIES = 3;                                // 409 뒤 다시 고르는 횟수
const HOLD_TTL = 300;                                 // APP_SEAT_HOLD_TTL_SECONDS (staging 기본)
const OPEN_AT = ENTRY + 5;                            // T0 (테스트 시작 기준 초)
const VERIFY_AFTER = REHEARSAL ? 60 : HOLD_TTL + 70;  // T0 뒤 확인 시각 — 방치 선점(최대 T0+40초 생성)이 300초 뒤 만료된 다음
const VERIFY_AT = OPEN_AT + VERIFY_AFTER;
const END_AT = VERIFY_AT + 20;                        // 보는 사람이 SSE를 끊는 시각
const APPROVE_CARD = "4111111111111112";
const DECLINE_CARD = "4111111111110000";              // 끝자리 0000 = 거절(ADR-13)
const MOCK_KEY = __ENV.PAYMENT_MOCK_KEY || "";

// 토큰 1,000개(약 1.2MB)를 VU 2,000개가 각자 open()하면 메모리가 수 GB가 된다 → 한 번 읽어 나눠 쓴다
const TOKENS = new SharedArray("tokens", () => {
  const files = (__ENV.K6_TOKENS_FILES || __ENV.K6_TOKENS_FILE || "").split(",").map((f) => f.trim()).filter(Boolean);
  const out = [];
  for (const f of files) {
    for (const line of open(f).split(/\r?\n/)) {
      if (line.trim().length > 0) out.push(line.trim());
    }
  }
  return out;
});

const holdLatency = new Trend("hold_latency", true);
const snapshotLatency = new Trend("snapshot_latency", true);
const held = new Counter("hold_success");             // 선점 성공(예매 수)
const seatTaken = new Counter("hold_seat_taken");     // 409 SEAT_UNAVAILABLE (경쟁에 진 시도) — 많아야 정상
const gaveUp = new Counter("hold_gave_up");           // 빈 좌석이 없거나 재시도를 다 써서 포기한 사람
const activeHold = new Counter("hold_active_hold");   // 409 ACTIVE_HOLD_EXISTS — 0이어야 한다(토큰 겹침)
const other = new Counter("rush_unexpected");         // 4xx 등 예상 밖 응답 — 0이어야 한다
const serverErrors = new Counter("server_errors");    // 5xx·연결 오류 (결제 경로의 503 PAYMENT_RESULT_UNKNOWN 제외)
const confirmed = new Counter("pay_confirmed");
const declined = new Counter("pay_declined");
const confirmedSeats = new Counter("seats_confirmed");
const released = new Counter("hold_released");
const abandoned = new Counter("hold_abandoned");
const unresolved = new Counter("payment_unresolved");
const sseEndedEarly = new Counter("sse_ended_early"); // 끝나기 전에 서버·중간 경로가 스트림을 닫음
const sseErrors = new Counter("sse_errors");          // 연결 자체 실패(시간 초과 아님)
const finalHeld = new Gauge("final_held_seats");      // 확인 시각에 남은 HELD — 0이어야 한다
const finalReserved = new Gauge("final_reserved_delta"); // 시작 대비 RESERVED 증가(음수일 수 있어 Gauge)
const verified = new Counter("rush_verified");

export const options = {
  discardResponseBodies: true,                        // 필요한 응답만 responseType text로 받는다
  setupTimeout: "60s",
  scenarios: {
    watchers: {
      executor: "per-vu-iterations", exec: "watch", vus: USERS, iterations: 1,
      maxDuration: `${END_AT + 30}s`, gracefulStop: "10s",
    },
    buyers: {
      executor: "per-vu-iterations", exec: "buy", vus: USERS, iterations: 1,
      startTime: `${OPEN_AT}s`, maxDuration: `${VERIFY_AT - OPEN_AT}s`, gracefulStop: "10s",
    },
    verify: {
      executor: "per-vu-iterations", exec: "verifyEnd", vus: 1, iterations: 1,
      startTime: `${VERIFY_AT}s`, maxDuration: "30s",
    },
  },
  thresholds: {
    hold_latency: ["p(95)<300"],                       // 데모 합격선
    server_errors: ["count==0"],
    hold_active_hold: ["count==0"],
    rush_unexpected: ["count==0"],
    payment_unresolved: ["count==0"],
    hold_success: ["count>0"],
    sse_ended_early: ["count==0"],
    sse_errors: ["count==0"],
    final_held_seats: ["value==0"],
    rush_verified: ["count==1"],                       // 확인이 실제로 돌았는지
  },
};

function snapshot(screeningId) {
  const res = http.get(`${BASE}/screenings/${screeningId}/seats`, {
    responseType: "text", tags: { name: "GET /screenings/{id}/seats" },
  });
  return res;
}

function countByStatus(res) {
  const counts = { AVAILABLE: 0, HELD: 0, RESERVED: 0 };
  for (const s of res.json("seats") || []) counts[s.status] = (counts[s.status] || 0) + 1;
  return counts;
}

export function setup() {
  if (TOKENS.length < USERS) {
    throw new Error(`토큰 ${TOKENS.length}개 < USERS ${USERS} — 회차당 선점 1건 제한 때문에 사는 사람마다 다른 사용자가 필요하다`);
  }
  if (BASE.includes("gateway:8080") && __ENV.ALLOW_INTERNAL !== "1") {
    throw new Error("BASE_URL이 클러스터 안 gateway다 — 오픈런은 화면과 같은 CloudFront 경로로 잰다(-e BASE_URL=https://{배포ID}.cloudfront.net/api)");
  }
  const screeningId = intEnv("RUSH_SCREENING_ID", "활성 좌석이 전부 비어 있는 회차 — tools/pick_targets.py --rush");
  const res = snapshot(screeningId);
  if (res.status !== 200) throw new Error(`좌석 스냅샷 ${res.status} — RUSH_SCREENING_ID·BASE_URL 확인`);
  const base = countByStatus(res);
  if (base.HELD > 0) throw new Error(`시작 전에 HELD ${base.HELD}석이 있다 — 지난 실행의 선점이 남았다(300초 기다리거나 다른 회차)`);
  console.log(`오픈런 회차 ${screeningId}: 빈 좌석 ${base.AVAILABLE} · 확정 ${base.RESERVED} · 사용자 ${USERS} · ${REHEARSAL ? "리허설" : "본 실행"}`);
  return { screeningId, baseReserved: base.RESERVED };
}

// ---- 보는 사람: SSE를 열어 두기만 한다 ------------------------------------
export function watch(data) {
  const i = exec.scenario.iterationInTest;               // 0 … USERS-1
  const enterAt = (i / USERS) * ENTRY;
  sleep(enterAt);
  const res = http.get(`${BASE}/screenings/${data.screeningId}/seats/stream`, {
    headers: { Accept: "text/event-stream" },
    timeout: `${Math.max(10, END_AT - enterAt)}s`,      // 끝까지 유지 → 정해진 시각에 시간 초과로 끊는다(정상)
    tags: { name: "GET /screenings/{id}/seats/stream" },
  });
  if (res.error_code === 1050) return;                   // 요청 시간 초과 = 계획대로 끊음
  if (res.status === 200) sseEndedEarly.add(1);          // 서버·ALB·CloudFront가 먼저 닫았다
  else sseErrors.add(1);
}

// ---- 사는 사람 ---------------------------------------------------------------
function track(res, allowResultUnknown) {
  const exempt = allowResultUnknown && res.status === 503 && reasonOf(res) === "PAYMENT_RESULT_UNKNOWN";
  if ((res.status === 0 || res.status >= 500) && !exempt) serverErrors.add(1);
  return res;
}

/** 1석 15% · 2석 60% · 3석 10% · 4석 15% */
function seatCount() {
  const r = Math.random();
  return r < 0.15 ? 1 : r < 0.75 ? 2 : r < 0.85 ? 3 : 4;
}

/** 빈 좌석 중 임의 위치에서 이어지는 n석(좌석 ID 순서 = 같은 줄 옆자리가 대부분) */
function choose(available, n) {
  const k = Math.min(n, available.length);
  const start = Math.floor(Math.random() * (available.length - k + 1));
  return available.slice(start, start + k);
}

const SETTLED = ["CONFIRMED", "PAYMENT_FAILED", "EXPIRED", "CANCELED"];

function pollUntilSettled(headers, bookingId) {
  for (let i = 0; i < 5; i++) {
    sleep(2);
    const res = track(http.get(`${BASE}/bookings/${bookingId}`, {
      headers, responseType: "text", tags: { name: "GET /bookings/{id}" },
    }), false);
    if (res.status === 200 && SETTLED.includes(res.json("status"))) return res.json("status");
  }
  return null;
}

function hold(data, token) {
  const wanted = seatCount();
  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    const snap = track(snapshot(data.screeningId), false);
    snapshotLatency.add(snap.timings.duration);
    if (snap.status !== 200) { if (snap.status < 500 && snap.status !== 0) other.add(1); return null; }
    const available = (snap.json("seats") || []).filter((s) => s.status === "AVAILABLE").map((s) => s.seatId).sort((a, b) => a - b);
    if (available.length === 0) { gaveUp.add(1); return null; }     // 매진

    const res = track(http.post(`${BASE}/seats/hold`,
      JSON.stringify({ screeningId: data.screeningId, seatIds: choose(available, wanted) }),
      { headers: authHeaders(token), responseType: "text", tags: { name: "POST /seats/hold" } }), false);
    holdLatency.add(res.timings.duration);

    if (res.status === 201) {
      held.add(1);
      return { bookingId: res.json("bookingId"), seatCount: res.json("seatCount") };
    }
    if (res.status === 409 && res.json("code") === "SEAT_UNAVAILABLE") {
      seatTaken.add(1);
      sleep(0.5 + Math.random());                         // 화면이 갱신되고 다시 고르는 시간
      continue;
    }
    if (res.status === 409) activeHold.add(1);
    else if (res.status !== 0 && res.status < 500) other.add(1);
    return null;
  }
  gaveUp.add(1);
  return null;
}

export function buy(data) {
  const i = exec.scenario.iterationInTest;
  const token = TOKENS[i];
  sleep((i / USERS) * RUSH);                              // 10초에 고르게 퍼진 첫 클릭

  const booking = hold(data, token);
  if (booking === null) return;

  const r = REHEARSAL ? 0.75 : Math.random();          // 리허설은 전부 해제(0.70~0.85 구간)
  if (r >= 0.85) { abandoned.add(1); return; }            // 방치 → 300초 뒤 만료

  sleep(5 + Math.random() * 15);                          // 결제창에서 고민하는 시간
  const headers = authHeaders(token);

  if (r >= 0.70) {                                        // 해제 (리허설은 전부 여기)
    const res = track(http.del(`${BASE}/bookings/${booking.bookingId}/hold`, null, {
      headers, tags: { name: "DELETE /bookings/{id}/hold" } }), false);
    if (res.status === 200 || res.status === 204) released.add(1);
    else if (res.status !== 0 && res.status < 500) other.add(1);
    return;
  }

  const card = Math.random() < 0.05 ? DECLINE_CARD : APPROVE_CARD;
  const res = track(http.post(`${BASE}/bookings/${booking.bookingId}/pay`,
    JSON.stringify({ method: "CARD", card: { number: card, expiry: "12/28", holder: "K6" } }),
    { headers: { ...headers, "X-Payment-Mock-Key": MOCK_KEY }, responseType: "text", tags: { name: "POST /bookings/{id}/pay" } }),
  true);
  let status = null;
  if (res.status === 200) {
    status = res.json("status");
  } else if (res.status === 503 && reasonOf(res) === "PAYMENT_RESULT_UNKNOWN") {
    status = pollUntilSettled(headers, booking.bookingId);
    if (status === null) unresolved.add(1);
  } else if (res.status !== 0 && res.status < 500) {
    other.add(1);                                         // 400 MOCK_NOT_ALLOWED면 결제 키가 CloudFront를 못 지난 것
  }
  check(res, { "결제 200 또는 503": (x) => x.status === 200 || x.status === 503 });
  if (status === "CONFIRMED") { confirmed.add(1); confirmedSeats.add(booking.seatCount); }
  else if (status === "PAYMENT_FAILED") declined.add(1);
}

// ---- 정합성 ------------------------------------------------------------------
export function verifyEnd(data) {
  const res = snapshot(data.screeningId);
  if (res.status !== 200) { other.add(1); return; }
  const c = countByStatus(res);
  finalHeld.add(c.HELD);
  finalReserved.add(c.RESERVED - data.baseReserved);
  verified.add(1);
}

export function handleSummary(data) {
  const n = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  const t = (name, p) => {
    const m = data.metrics[name];
    const v = m && m.values[p];
    return v === undefined ? "-" : `${v.toFixed(1)}ms`;
  };
  const g = (name) => (data.metrics[name] ? data.metrics[name].values.value : "-");
  const reservedOk = g("final_reserved_delta") === n("seats_confirmed");
  const verdicts = Object.entries(data.metrics)
    .filter(([, m]) => m.thresholds)
    .map(([name, m]) => `  ${Object.values(m.thresholds).every((x) => x.ok) ? "✓" : "✗"} ${name}: ${Object.keys(m.thresholds).join(", ")}`);
  return {
    stdout: [
      "",
      `오픈런 결과 (${REHEARSAL ? "리허설" : "본 실행"}, 사용자 ${USERS})`,
      `  선점 성공 ${n("hold_success")} · 경쟁에 진 시도(409) ${n("hold_seat_taken")} · 포기(매진·재시도 소진) ${n("hold_gave_up")}`,
      `  결제 확정 ${n("pay_confirmed")}건 ${n("seats_confirmed")}석 · 거절 ${n("pay_declined")} · 해제 ${n("hold_released")} · 방치 ${n("hold_abandoned")}`,
      `  0이어야 하는 것: 5xx·연결 오류 ${n("server_errors")} · 사용자 제한 409 ${n("hold_active_hold")} · 예상 밖 ${n("rush_unexpected")} · 결과 미확인 미해결 ${n("payment_unresolved")}`,
      `  SSE: 먼저 끊김 ${n("sse_ended_early")} · 연결 실패 ${n("sse_errors")}  (연결 수는 Grafana booking_sse_connections, 반영 지연은 sse_lag.py)`,
      "",
      `선점 p95 ${t("hold_latency", "p(95)")} · 중앙값 ${t("hold_latency", "med")} · 최대 ${t("hold_latency", "max")}   ← p95 300ms 이하`,
      `스냅샷 p95 ${t("snapshot_latency", "p(95)")}`,
      "",
      `정합성 (확인 ${n("rush_verified") === 1 ? "함" : "못 함"})`,
      `  남은 HELD ${g("final_held_seats")}석   ← 0이어야 한다`,
      `  ${reservedOk ? "✓" : "✗"} RESERVED 증가 ${g("final_reserved_delta")}석 = 확정받은 좌석 ${n("seats_confirmed")}석`,
      "",
      "임계값",
      ...verdicts,
      "",
    ].join("\n"),
  };
}
