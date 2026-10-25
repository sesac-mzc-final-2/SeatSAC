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
//
// 왜 확정 뒤에 취소하나: 확정된 좌석은 다시 잡을 수 없다. 취소하지 않으면
// SEATS_PER_VU번 반복한 뒤부터 선점이 전부 409가 되고 결제 경로가 아예 돌지
// 않는다 — 배포 중 오류율을 재는 시나리오가 소리 없이 헛돈다.
import http from "k6/http";
import { check, group, sleep } from "k6";
import { Counter, Rate } from "k6/metrics";
import { BASE, authHeaders, reasonOf, requireDistinctTokens, tokenForVu } from "./lib/common.js";

const VUS = Number(__ENV.VUS || 10);
const SEATS_PER_VU = Number(__ENV.SEATS_PER_VU || 20);
const CARD = __ENV.PAY_CARD || "4111111111111112";

const serverErrors = new Counter("server_errors");          // 5xx·연결 오류 — 0이어야 한다
const resultUnknown = new Counter("payment_result_unknown"); // 503 PAYMENT_RESULT_UNKNOWN (지연 카드 경로)
const confirmed = new Counter("bookings_confirmed");
const canceled = new Counter("bookings_canceled");       // 좌석 반납 — confirmed와 같아야 한다
const unresolved = new Counter("payment_unresolved");    // 503 뒤 종료 상태를 못 본 건 — 0이어야 한다
const seatBusy = new Rate("hold_conflict_rate");         // 좌석 반납이 밀린 비율
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
    // 503 뒤 종료 상태(CONFIRMED·PAYMENT_FAILED·EXPIRED·CANCELED)를 끝까지 확인해야 한다
    payment_unresolved: ["count==0"],
    // 좌석이 계속 돌아야 한다 — 10%를 넘으면 취소가 밀렸거나 좌석 구간이 겹친 것이다
    hold_conflict_rate: ["rate<0.1"],
    // check 실패는 기본적으로 run을 실패시키지 않는다. 임계값으로 묶어 둔다
    checks: ["rate==1"],
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

/**
 * 5xx·연결 오류를 센다.
 *
 * 면제는 **결제 경로의 503 PAYMENT_RESULT_UNKNOWN 하나뿐이다** — 그것만 설계상
 * 「결과 미확인」이고 화면이 단건 조회로 복구한다. 503을 통째로 빼면 배포 중
 * gateway가 내는 503 SERVICE_UNAVAILABLE(= 무중단 실패)까지 묻힌다.
 */
function track(res, opts) {
  const exempt = (opts && opts.allowResultUnknown) === true
    && res.status === 503
    && reasonOf(res) === "PAYMENT_RESULT_UNKNOWN";
  const failed = (res.status === 0 || res.status >= 500) && !exempt;
  errorRate.add(failed);
  if (failed) {
    serverErrors.add(1);
  }
  return res;
}

const SETTLED = ["CONFIRMED", "PAYMENT_FAILED", "EXPIRED", "CANCELED"];

/**
 * 503(결과 미확인) 뒤 단건 조회로 종료 상태를 확인한다. PENDING이면 아직 결과가
 * 나오지 않은 것이니 다시 묻는다. 끝까지 못 보면 null — 그 건은 payment_unresolved다.
 * 지연 카드(9999)가 5초 뒤 승인되므로 2초 × 5번이면 충분하다.
 */
function pollUntilSettled(token, bookingId) {
  for (let i = 0; i < 5; i++) {
    sleep(2);
    const res = track(http.get(`${BASE}/bookings/${bookingId}`, {
      headers: authHeaders(token), tags: { name: "GET /bookings/{id}" },
    }));
    if (res.status !== 200) {
      continue;
    }
    const status = res.json("status");
    if (SETTLED.includes(status)) {
      return status;
    }
  }
  return null;
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
    seatBusy.add(res.status === 409);
    if (res.status === 201) {
      bookingId = res.json("bookingId");
    }
  });

  if (bookingId === null) {
    sleep(1);
    return;
  }

  let settled = null;
  group("결제", () => {
    const res = track(http.post(
      `${BASE}/bookings/${bookingId}/pay`,
      JSON.stringify({ method: "CARD", card: { number: CARD, expiry: "12/28", holder: "K6" } }),
      { headers: authHeaders(token), tags: { name: "POST /bookings/{id}/pay" } }
    ), { allowResultUnknown: true });
    check(res, { "결제 200 또는 409·503": (r) => [200, 409, 503].includes(r.status) });

    if (res.status === 200) {
      settled = res.json("status");
    } else if (res.status === 503) {
      // 결과 미확인 — 지연 카드이거나 전환 중 한쪽이 끊긴 경우.
      // 단건 조회가 200인 것만 보면 안 된다. PENDING이면 아직 결과가 안 나온 것이라
      // 「복구됐다」고 말할 수 없다 → 종료 상태가 될 때까지 다시 묻는다
      resultUnknown.add(1);
      settled = pollUntilSettled(token, bookingId);
      check(settled, { "503 뒤 종료 상태 확인": (s) => s !== null });
      if (settled === null) {
        unresolved.add(1);
      }
    }

    if (settled === "CONFIRMED") {
      confirmed.add(1);
    }
  });

  // 확정했으면 좌석을 반납한다 — 그래야 다음 바퀴에 같은 좌석을 다시 쓸 수 있다
  if (settled === "CONFIRMED") {
    group("취소", () => {
      const res = track(http.post(`${BASE}/bookings/${bookingId}/cancel`, null, {
        headers: authHeaders(token), tags: { name: "POST /bookings/{id}/cancel" },
      }));
      check(res, { "취소 200": (r) => r.status === 200 });
      if (res.status === 200) {
        canceled.add(1);
      }
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
      `  반납(취소)한 예매   ${count("bookings_canceled")}   ← 확정 수와 같아야 좌석이 돈다`,
      `  결과 미확인 미해결   ${count("payment_unresolved")}   ← 0이어야 한다`,
      `  결과 미확인(503)    ${count("payment_result_unknown")}   ← 지연 카드가 아니면 0이 좋다`,
      `  5xx·연결 오류      ${count("server_errors")}   ← 0이어야 한다`,
      "",
    ].join("\n"),
  };
}
