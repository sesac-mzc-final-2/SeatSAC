// 무중단 배포 데모: 블루/그린 전환 중 오류율 0% (결제 경로 포함)
//
// 돌리는 방법 — 이 스크립트를 띄워 둔 상태에서 booking(또는 gateway) 새 버전을 배포한다.
// 전환 전후로 5xx·연결 오류가 0이어야 한다.
//
//   K6_TOKENS       서로 다른 테스트 계정 토큰 (VU 수만큼)
//   SCREENING_ID    좌석이 넉넉한 회차 (VU × 반복 × 1석 이상)
//   SEAT_ID_FROM    쓸 좌석 ID 시작값 — VU마다 서로 다른 좌석을 잡아 좌석 경쟁을 피한다
//                   (경쟁 테스트는 seat-hold-contention.js가 따로 한다)
//   PAY_CARD        결제에 쓸 카드 번호 (기본 4111111111111112 = 승인)
//
// 왜 좌석을 VU마다 나누나: 이 시나리오가 재는 것은 「배포 중에 오류가 나는가」다.
// 같은 좌석을 다투면 409가 섞여 그 신호가 가려진다.
import http from "k6/http";
import { check, group, sleep } from "k6";
import { Counter, Rate } from "k6/metrics";
import { BASE, authHeaders, requireDistinctTokens, tokenForVu } from "./lib/common.js";

const VUS = Number(__ENV.VUS || 10);
const SEATS_PER_VU = Number(__ENV.SEATS_PER_VU || 20);
const CARD = __ENV.PAY_CARD || "4111111111111112";

const serverErrors = new Counter("server_errors");          // 5xx·연결 오류 — 0이어야 한다
const resultUnknown = new Counter("payment_result_unknown"); // 503 PAYMENT_RESULT_UNKNOWN (지연 카드 경로)
const confirmed = new Counter("bookings_confirmed");
const errorRate = new Rate("request_errors");

export const options = {
  scenarios: {
    load: {
      executor: "constant-vus",
      vus: VUS,
      duration: __ENV.DURATION || "5m", // 전환 + scaleDownDelay 60초를 넉넉히 덮는다
    },
  },
  thresholds: {
    // 데모 조건: 배포 중 오류율 0%
    server_errors: ["count==0"],
    request_errors: ["rate==0"],
    // 결제 경로가 실제로 돌았는지 (0이면 시나리오가 헛돈 것이다)
    bookings_confirmed: ["count>0"],
  },
};

export function setup() {
  requireDistinctTokens(VUS);
  return {
    screeningId: Number(__ENV.SCREENING_ID),
    seatFrom: Number(__ENV.SEAT_ID_FROM),
  };
}

/** VU마다 자기 좌석 구간에서 반복마다 다른 좌석을 고른다 */
function seatFor(data, vu, iter) {
  return data.seatFrom + (vu - 1) * SEATS_PER_VU + (iter % SEATS_PER_VU);
}

function track(res) {
  const failed = res.status === 0 || res.status >= 500;
  errorRate.add(failed && res.status !== 503);
  if (res.status === 0 || (res.status >= 500 && res.status !== 503)) {
    serverErrors.add(1);
  }
  return res;
}

export default function (data) {
  const token = tokenForVu(__VU);
  const seatId = seatFor(data, __VU, __ITER);
  let bookingId = null;

  group("조회", () => {
    track(http.get(`${BASE}/screenings/${data.screeningId}/seats`, {
      tags: { name: "GET /screenings/{id}/seats" },
    }));
  });

  group("선점", () => {
    const res = track(http.post(
      `${BASE}/seats/hold`,
      JSON.stringify({ screeningId: data.screeningId, seatIds: [seatId] }),
      { headers: authHeaders(token), tags: { name: "POST /seats/hold" } }
    ));
    check(res, { "선점 201 또는 409": (r) => r.status === 201 || r.status === 409 });
    if (res.status === 201) {
      bookingId = res.json("bookingId");
    }
  });

  if (bookingId !== null) {
    group("결제", () => {
      const res = track(http.post(
        `${BASE}/bookings/${bookingId}/pay`,
        JSON.stringify({ method: "CARD", card: { number: CARD, expiry: "12/28", holder: "K6" } }),
        { headers: authHeaders(token), tags: { name: "POST /bookings/{id}/pay" } }
      ));

      if (res.status === 200 && res.json("status") === "CONFIRMED") {
        confirmed.add(1);
      } else if (res.status === 503) {
        // 결과 미확인 — 지연 카드이거나 전환 중 한쪽이 끊긴 경우. 단건 조회로 확인한다
        resultUnknown.add(1);
        const detail = track(http.get(`${BASE}/bookings/${bookingId}`, {
          headers: authHeaders(token), tags: { name: "GET /bookings/{id}" },
        }));
        check(detail, { "단건 조회 200": (r) => r.status === 200 });
      }
      check(res, { "결제 200 또는 409·503": (r) => [200, 409, 503].includes(r.status) });
    });
  }

  sleep(1);
}

export function handleSummary(data) {
  const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
  return {
    stdout: [
      "",
      "배포 중 부하 결과",
      `  확정된 예매        ${count("bookings_confirmed")}`,
      `  결과 미확인(503)    ${count("payment_result_unknown")}   ← 지연 카드가 아니면 0이 좋다`,
      `  5xx·연결 오류      ${count("server_errors")}   ← 0이어야 한다`,
      "",
    ].join("\n"),
  };
}
