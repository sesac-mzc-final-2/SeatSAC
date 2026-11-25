// 로컬 화면 작업용 목 서버 — catalog·user 계약(contracts/openapi/{catalog,user}.openapi.yaml)과 booking 의
// 예매 취소(BKG-07) 모양으로 응답한다. 의존성 없음(Node 내장 http). 데이터는 전부 이 파일 안의 가짜 값이다
// (롯데시네마 수집 데이터·포스터 없음). 예매·선호는 메모리에만 있어 재시작하면 초기값으로 돌아간다.
//
//   npm run mock                        # http://localhost:8089
//   npm run dev:mock                    # 목 + Vite(/api → 8089) 한 번에
//   DEV_SESSION=1 npm run dev:mock      # + 가짜 로그인 → 마이페이지(/me)까지 (Authorization 헤더가 있으면 누구든 통과)
//   MOCK_PORT=9000 npm run mock
//
// 재현용: MOCK_FAIL_PATH=/api/screenings (화면에서 503 오류 상태) · 직접 호출은 ?fail=503 · /api/movies/999 → 404
//        예매 ID 9001 은 회차 정보 없음(screening null)
// AI 챗봇(ai.openapi.yaml): MOCK_AI=off → /api/ai/** 404(gateway 플래그 off, 버튼 숨김) · MOCK_AI=down → 503(ai 파드 없음)
//        질문에 #409 · #429 · #429day · #503 · #timeout · #snapshot 을 넣으면 그 오류로 응답
//        문맥 이어받기(3.4.22): 답마다 context, 영화 버튼의 continueFrom 이면 그 답의 날짜·극장·시각(○시 이후)으로 좁혀 답한다
//          예: 「오늘 20시 이후 영화 알려줘」 → 답 아래 영화 버튼 → 오늘 · 20시 이후 · 그 영화만
// 쿠폰(coupon.openapi.yaml, 6.3.6): MOCK_COUPON=open(기본, 진행 중) · upcoming(1분 뒤 시작 — 카운트다운) · soldout · off(404, 배너 없음)
//        MOCK_GRADE=NORMAL 이면 /users/me grade NORMAL → 「VIP 전용」, 받기는 403 COUPON_NOT_ELIGIBLE (기본 VIP)
import { createServer } from "node:http";
import { createHash, randomUUID } from "node:crypto";

const PORT = Number(process.env.MOCK_PORT ?? 8089);
/** 화면에서 오류 상태를 재현할 때: 이 경로는 항상 MOCK_FAIL_STATUS(기본 503)로 응답 (예: MOCK_FAIL_PATH=/api/screenings) */
const FAIL_PATH = process.env.MOCK_FAIL_PATH ?? "";
const FAIL_STATUS = Number(process.env.MOCK_FAIL_STATUS ?? 503);
const KST = "+09:00";
/** 편성이 있는 기간: 오늘 ±30일. 밖은 계약대로 `{ items: [] }`, 단건도 404 (목록·단건 범위 동일) */
const SEED_RANGE_DAYS = 30;

// ---------- 데이터 ----------

const movies = [
  { movieId: 1, title: "별이 지는 항구", titleEn: "Harbor of Falling Stars", viewGrade: "12", slotMinutes: 128, runtime: 118, genres: ["드라마"], hasPoster: true },
  { movieId: 2, title: "미드나잇 러너 2", titleEn: "Midnight Runner 2", viewGrade: "15", slotMinutes: 135, runtime: 124, genres: ["액션", "범죄"], hasPoster: true },
  { movieId: 3, title: "고양이 탐정단: 사라진 생선", titleEn: null, viewGrade: "ALL", slotMinutes: 102, runtime: 92, genres: ["애니메이션", "가족"], hasPoster: false },
  { movieId: 4, title: "붉은 서재", titleEn: "The Crimson Study", viewGrade: "19", slotMinutes: 142, runtime: null, genres: ["스릴러"], hasPoster: true },
  { movieId: 5, title: "여름, 그리고 우리", titleEn: "That Summer", viewGrade: "12", slotMinutes: 115, runtime: 105, genres: [], hasPoster: false },
  { movieId: 6, title: "오케스트라의 밤", titleEn: "Night of the Orchestra", viewGrade: "ALL", slotMinutes: 120, runtime: 110, genres: ["음악", "다큐멘터리"], hasPoster: true },
];

/** 배치 있는 극장 4곳 (ID는 staging 과 같게) */
const theaters = [
  { theaterId: 4, name: "건대입구", region: "서울", hasLayout: true },
  { theaterId: 16, name: "영등포", region: "서울", hasLayout: true },
  { theaterId: 18, name: "월드타워", region: "서울", hasLayout: true },
  { theaterId: 118, name: "부산본점", region: "부산·울산·경남", hasLayout: true },
  { theaterId: 1, name: "가산디지털", region: "서울", hasLayout: false },
];

const auditoriums = [
  { auditoriumId: 401, theaterId: 4, name: "1관", screenType: "RECLINER", totalSeats: 105 },
  { auditoriumId: 402, theaterId: 4, name: "2관", screenType: "NORMAL", totalSeats: 184 },
  { auditoriumId: 403, theaterId: 4, name: "6관", screenType: "NORMAL", totalSeats: 262 },
  { auditoriumId: 1601, theaterId: 16, name: "1관", screenType: "NORMAL", totalSeats: 227 },
  { auditoriumId: 1602, theaterId: 16, name: "5관", screenType: "NORMAL", totalSeats: 117 },
  { auditoriumId: 1801, theaterId: 18, name: "3관", screenType: "RECLINER", totalSeats: 91 },
  { auditoriumId: 1802, theaterId: 18, name: "7관", screenType: "NORMAL", totalSeats: 206 },
  { auditoriumId: 11801, theaterId: 118, name: "2관", screenType: "NORMAL", totalSeats: 215 },
  { auditoriumId: 11802, theaterId: 118, name: "4관", screenType: "RECLINER", totalSeats: 129 },
];

/** 상영관별 하루 편성 틀: [movieId, "HH:mm" ...]. 날짜마다 같은 편성을 날짜만 바꿔 돌려준다 */
const dailyTemplate = {
  401: [[1, "10:20"], [1, "13:00"], [4, "15:40"], [4, "18:30"], [2, "21:10"]],
  402: [[3, "10:00"], [3, "12:10"], [5, "14:20"], [2, "16:40"], [2, "19:20"], [2, "22:00"]],
  403: [[6, "11:00"], [1, "13:30"], [6, "16:00"], [4, "18:40"], [1, "21:30"]],
  1601: [[2, "10:30"], [2, "13:10"], [1, "15:50"], [1, "18:30"], [4, "21:10"]],
  1602: [[3, "11:20"], [5, "13:30"], [5, "15:50"], [6, "18:00"]],
  1801: [[4, "12:00"], [4, "14:50"], [1, "17:40"], [2, "20:30"]],
  1802: [[3, "10:10"], [6, "12:20"], [2, "14:40"], [5, "17:20"], [2, "19:40"], [4, "22:20"]],
  11801: [[1, "10:40"], [2, "13:20"], [1, "16:00"], [2, "18:40"], [4, "21:20"]],
  11802: [[3, "11:10"], [6, "13:20"], [5, "15:40"], [6, "18:00"], [1, "20:20"]],
};

// ---------- 유틸 ----------

const pad = (n) => String(n).padStart(2, "0");
const todayKst = () =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" })
    .formatToParts(new Date())
    .reduce((acc, p) => ({ ...acc, [p.type]: p.value }), {});
const todayIso = () => {
  const p = todayKst();
  return `${p.year}-${p.month}-${p.day}`;
};
/** `YYYY-MM-DD` 형식이고 달력에 있는 날짜인지 (2026-10-32 같은 값은 400 으로) */
const isIsoDate = (s) => {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s ?? "");
  if (!match) return false;
  const [year, month, day] = match.slice(1).map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth[month - 1];
};
/** page·size: 0 이상의 안전 정수 */
const isNonNegativeInt = (s) => /^\d+$/.test(s ?? "") && Number.isSafeInteger(Number(s));
/** 계약의 integer(int64) ID: 1 ~ 2^63-1 (BigInt 로 범위만 확인. 목 데이터 ID 는 작아 이후 비교는 Number 로 충분) */
const INT64_MAX = 9223372036854775807n;
const isPositiveIntId = (s) => /^\d+$/.test(s ?? "") && BigInt(s) > 0n && BigInt(s) <= INT64_MAX;
const dayNumber = (iso) => Math.floor(Date.UTC(...iso.split("-").map((v, i) => Number(v) - (i === 1 ? 1 : 0))) / 86400000);
const addMinutes = (iso, hhmm, minutes) => {
  const [h, m] = hhmm.split(":").map(Number);
  const base = new Date(`${iso}T${pad(h)}:${pad(m)}:00${KST}`);
  const d = new Date(base.getTime() + minutes * 60000);
  // KST 로 다시 표기 (+9h 후 UTC 필드 읽기)
  const k = new Date(d.getTime() + 9 * 3600 * 1000);
  return `${k.getUTCFullYear()}-${pad(k.getUTCMonth() + 1)}-${pad(k.getUTCDate())}T${pad(k.getUTCHours())}:${pad(k.getUTCMinutes())}:00${KST}`;
};
const inSeedRange = (dateIso) => Math.abs(dayNumber(dateIso) - dayNumber(todayIso())) <= SEED_RANGE_DAYS;
const traceId = () => randomUUID().replace(/-/g, "");

const json = (res, status, body, responseTraceId = traceId()) => {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "X-Trace-Id": responseTraceId });
  res.end(JSON.stringify(body));
};
/** 오류는 헤더 X-Trace-Id 와 본문 traceId 가 같은 값 (실제 Gateway 와 동일) */
const fail = (res, status, code, message, details = []) => {
  const responseTraceId = traceId();
  return json(res, status, { code, message, traceId: responseTraceId, details }, responseTraceId);
};

/** gateway application.yml 라우트 순서를 흉내 낸 라우트 ID (좌석 현황·SSE가 /screenings/** 보다 먼저) */
const gatewayRouteIdOf = (path) => {
  if (/^\/api\/screenings\/[^/]+\/seats\/stream$/.test(path)) return "booking-seat-stream";
  if (/^\/api\/screenings\/[^/]+\/seats$/.test(path)) return "booking-seats";
  if (/^\/api\/(seats|bookings)(\/|$)/.test(path)) return "booking";
  if (/^\/api\/(movies|screenings|theaters|auditoriums)(\/|$)/.test(path)) return "catalog";
  if (/^\/api\/users(\/|$)/.test(path)) return "user";
  return null;
};

// ---------- 응답 조립 ----------

const posterUrl = (m) => (m.hasPoster ? `http://localhost:${PORT}/mock-poster/${m.movieId}.svg` : null);

const movieSummary = (m) => ({
  movieId: m.movieId,
  title: m.title,
  titleEn: m.titleEn,
  viewGrade: m.viewGrade,
  slotMinutes: m.slotMinutes,
  posterUrl: posterUrl(m),
});

const movieDetail = (m) => ({
  ...movieSummary(m),
  synopsis:
    m.movieId % 3 === 0
      ? null
      : m.movieId % 2 === 0
        ? `A placeholder synopsis for ${m.titleEn ?? m.title}. Generated by the local mock server — not real film data.`
        : `${m.title}의 줄거리입니다. 로컬 목 서버가 만든 문장으로 실제 영화 정보가 아닙니다.\n\n두 번째 문단입니다. 화면에서 줄바꿈이 유지되는지 확인합니다.`,
  director: m.movieId % 4 === 0 ? null : `감독 ${m.movieId}`,
  cast: m.movieId === 5 ? [] : [`배우 A${m.movieId}`, `배우 B${m.movieId}`, `배우 C${m.movieId}`, `배우 D${m.movieId}`, `배우 E${m.movieId}`, `배우 F${m.movieId}`],
  releaseDate: m.movieId % 5 === 0 ? null : `2026-${pad((m.movieId % 12) + 1)}-15`,
  genres: m.genres,
  runtimeMinutes: m.runtime,
});

/** 회차 ID = 날짜번호·상영관·순번으로 결정적 */
const screeningId = (dayNo, auditoriumId, seq) => (dayNo % 1000) * 1000000 + auditoriumId * 10 + seq;

const remaining = (id, total) => {
  if (id % 13 === 0) return { remainingSeats: 0, remainingSeatsSource: "BOOKING" };
  if (id % 11 === 0) return { remainingSeats: total, remainingSeatsSource: "TOTAL" };
  const h = (id * 2654435761) % 4294967296;
  return { remainingSeats: total - (h % Math.max(1, Math.floor(total * 0.6))), remainingSeatsSource: "BOOKING" };
};

function screeningsOn(dateIso) {
  const dayNo = dayNumber(dateIso);
  const items = [];
  for (const a of auditoriums) {
    const theater = theaters.find((t) => t.theaterId === a.theaterId);
    (dailyTemplate[a.auditoriumId] ?? []).forEach(([movieId, start], seq) => {
      const m = movies.find((x) => x.movieId === movieId);
      const id = screeningId(dayNo, a.auditoriumId, seq + 1);
      items.push({
        screeningId: id,
        movie: { movieId: m.movieId, title: m.title, viewGrade: m.viewGrade },
        theater: { theaterId: theater.theaterId, name: theater.name },
        auditorium: { auditoriumId: a.auditoriumId, name: a.name, screenType: a.screenType },
        layoutVersion: 1,
        businessDate: dateIso,
        startsAt: addMinutes(dateIso, start, 0),
        endsAt: addMinutes(dateIso, start, m.slotMinutes),
        filmFormat: "2D",
        totalSeats: a.totalSeats,
        ...remaining(id, a.totalSeats),
      });
    });
  }
  return items.sort((x, y) => x.startsAt.localeCompare(y.startsAt));
}

function findScreening(id) {
  // 편성 기간(오늘 ±SEED_RANGE_DAYS) 안에서 찾는다 (ID 에 날짜번호가 들어 있다)
  const today = dayNumber(todayIso());
  for (let d = -SEED_RANGE_DAYS; d <= SEED_RANGE_DAYS; d++) {
    const dayNo = today + d;
    if (dayNo % 1000 !== Math.floor(id / 1000000)) continue;
    const date = new Date(dayNo * 86400000);
    const iso = `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
    const hit = screeningsOn(iso).find((s) => s.screeningId === id);
    if (hit) return hit;
  }
  return null;
}

// ---------- 사용자 (user 계약 · BKG-07) ----------

const DEV_USER = {
  userId: "00000000-0000-4000-8000-000000000001",
  email: "dev@localhost",
  name: "개발 세션",
  role: "USER",
  createdAt: "2026-10-28T09:00:00+09:00",
  grade: process.env.MOCK_GRADE === "NORMAL" ? "NORMAL" : "VIP",
};

const SEAT_PRICE = 15000;
const dateOffset = (days) => {
  const d = new Date((dayNumber(todayIso()) + days) * 86400000);
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
};
const minusMinutes = (iso, minutes) => new Date(new Date(iso).getTime() - minutes * 60000);
const toKstIso = (date) => {
  const k = new Date(date.getTime() + 9 * 3600 * 1000);
  return `${k.getUTCFullYear()}-${pad(k.getUTCMonth() + 1)}-${pad(k.getUTCDate())}T${pad(k.getUTCHours())}:${pad(k.getUTCMinutes())}:${pad(k.getUTCSeconds())}${KST}`;
};
/** USR-04 회차 요약 — 영화 3(고양이 탐정단)은 포스터가 없어 제목 카드가 보인다 */
const summaryOf = (s) => ({
  screeningId: s.screeningId,
  movieId: s.movie.movieId,
  movieTitle: s.movie.title,
  posterUrl: posterUrl(movies.find((m) => m.movieId === s.movie.movieId)),
  theaterName: s.theater.name,
  auditoriumName: s.auditorium.name,
  startsAt: s.startsAt,
  endsAt: s.endsAt,
});

/**
 * 예매 내역 초기값: 오늘 기준 ±며칠의 회차를 골라 상태 4종을 섞는다. 미래 회차의 CONFIRMED 만 취소 버튼이 보이고
 * 지난 회차의 CONFIRMED 는 보이지 않아야 한다. 9001 은 Catalog 실패(screening null) 재현용. 23건 → 「더 보기」(size 20) 확인
 */
function seedBookings() {
  const plan = [
    [2, 0, "CONFIRMED", ["H7", "H8"]],
    [1, 3, "CONFIRMED", ["E5"]],
    [0, 4, "CONFIRMED", ["C10", "C11", "C12"]],
    [-1, 1, "CONFIRMED", ["F3", "F4"]],
    [-1, 5, "PAYMENT_FAILED", ["B2"]],
    [-2, 2, "EXPIRED", ["G1", "G2"]],
    [-3, 0, "CANCELED", ["D6", "D7"]],
    [3, 2, "CONFIRMED", ["A1"]],
    [-4, 3, "CONFIRMED", ["J7", "J8"]],
    [-5, 1, "PAYMENT_FAILED", ["K4"]],
  ];
  const result = [];
  let id = 1001;
  for (let round = 0; round < 3 && result.length < 22; round++) {
    for (const [dayDelta, pick, status, seats] of plan) {
      if (result.length >= 22) break;
      const list = screeningsOn(dateOffset(dayDelta - round * 7));
      const s = list[(pick + round * 7) % list.length];
      const paid = status === "CONFIRMED" || status === "CANCELED";
      result.push({
        bookingId: id++,
        status,
        seatLabels: seats,
        seatCount: seats.length,
        amount: seats.length * SEAT_PRICE,
        createdAt: toKstIso(minusMinutes(s.startsAt, 60 * 48 + round * 90)),
        confirmedAt: paid ? toKstIso(minusMinutes(s.startsAt, 60 * 48 - 3)) : null,
        canceledAt: status === "CANCELED" ? toKstIso(minusMinutes(s.startsAt, 600)) : null,
        paymentId: status === "CANCELED" ? randomUUID() : null,
        screening: summaryOf(s),
      });
    }
  }
  result.push({
    bookingId: 9001,
    status: "CONFIRMED",
    seatLabels: ["E9", "E10"],
    seatCount: 2,
    amount: 2 * SEAT_PRICE,
    createdAt: toKstIso(new Date(Date.now() - 36e5)),
    confirmedAt: toKstIso(new Date(Date.now() - 35e5)),
    canceledAt: null,
    screening: null,
  });
  // 최근 생성 순
  return result.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

const bookings = seedBookings();
let preferences = {
  preferredTheaters: [
    { rank: 1, theaterId: 4 },
    { rank: 2, theaterId: 18 },
  ],
  preferredTimeSlot: "EVENING",
  preferredGenres: ["드라마", "모험"],
};
const TIME_SLOTS = ["MORNING", "AFTERNOON", "EVENING", "NIGHT"];
const BOOKING_STATUSES = ["CONFIRMED", "PAYMENT_FAILED", "EXPIRED", "CANCELED"];

/** 본문 JSON. 비어 있으면 null, 깨졌으면 undefined */
const readJson = (req) =>
  new Promise((resolve) => {
    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      try {
        resolve(raw ? JSON.parse(raw) : null);
      } catch {
        resolve(undefined);
      }
    });
  });

/** 극장 ID 가 배치 있는 극장이 아니면 400 (실제 user 는 Catalog GET /internal/theaters 로 확인) */
function validatePreferences(body) {
  if (!body || typeof body !== "object") return [{ field: "body", reason: "REQUIRED" }];
  const details = [];
  const picked = body.preferredTheaters;
  if (!Array.isArray(picked)) details.push({ field: "preferredTheaters", reason: "ARRAY" });
  else {
    if (picked.length > 3) details.push({ field: "preferredTheaters", reason: "MAX_3" });
    const ranks = new Set();
    for (const t of picked) {
      const rank = t?.rank;
      if (!Number.isInteger(rank) || rank < 1 || rank > 3 || ranks.has(rank)) {
        details.push({ field: "preferredTheaters[].rank", value: rank, reason: "RANK_1_3_UNIQUE" });
      }
      ranks.add(rank);
      if (!theaters.some((th) => th.hasLayout && th.theaterId === t?.theaterId)) {
        details.push({ field: "preferredTheaters[].theaterId", value: t?.theaterId, reason: "UNKNOWN_THEATER" });
      }
    }
  }
  if (body.preferredTimeSlot !== null && !TIME_SLOTS.includes(body.preferredTimeSlot)) {
    details.push({ field: "preferredTimeSlot", value: body.preferredTimeSlot, reason: "ENUM" });
  }
  if (!Array.isArray(body.preferredGenres) || body.preferredGenres.some((g) => typeof g !== "string")) {
    details.push({ field: "preferredGenres", reason: "STRING_ARRAY" });
  }
  return details;
}

const requiresAuth = (path) =>
  path.startsWith("/api/users/") ||
  path.startsWith("/api/bookings/") ||
  path.startsWith("/api/ai/") ||
  path.startsWith("/api/coupons/");

// ---------- 쿠폰 (coupon.openapi.yaml, CPN-01~03) ----------

const MOCK_COUPON = process.env.MOCK_COUPON ?? "open";
/** 이벤트 시각은 목 서버를 띄운 때 기준 (open: 10분 전 시작·50분 뒤 끝 · upcoming: 1분 뒤 시작) */
const couponEvent = (() => {
  const now = Date.now();
  const startsAt = MOCK_COUPON === "upcoming" ? now + 60_000 : now - 10 * 60_000;
  const total = 100;
  return {
    eventId: 3,
    title: "VIP 선착순 50% 할인",
    discountRate: 50,
    startsAt: kstIso(startsAt),
    endsAt: kstIso(startsAt + 60 * 60_000),
    totalQuantity: total,
    remaining: MOCK_COUPON === "soldout" ? 0 : 37,
    eligibleGrade: "VIP",
  };
})();
/** 받은 쿠폰 (메모리) · 멱등 키 → 첫 응답 */
const myCoupons = [];
const couponIdem = new Map();

function kstIso(ms) {
  return new Date(ms + 9 * 3600_000).toISOString().replace("Z", KST).replace(/\.\d{3}/, "");
}

function couponState(nowMs = Date.now()) {
  if (nowMs < Date.parse(couponEvent.startsAt)) return "UPCOMING";
  if (nowMs < Date.parse(couponEvent.endsAt)) return "OPEN";
  return "CLOSED";
}

function couponView(c) {
  return Date.parse(c.expiresAt) <= Date.now() && c.state === "ISSUED" ? { ...c, state: "EXPIRED" } : c;
}

async function handleCoupons(req, res, path) {
  if (path === "/api/coupons/me" && req.method === "GET") return json(res, 200, { items: myCoupons.map(couponView) });
  if (MOCK_COUPON === "off") return fail(res, 404, "NOT_FOUND", "no coupon event");

  if (path === "/api/coupons/events/current" && req.method === "GET") {
    const state = couponState();
    if (state === "CLOSED") return fail(res, 404, "NOT_FOUND", "no coupon event");
    const mine = myCoupons.find((c) => c.eventId === couponEvent.eventId);
    const { remaining, ...rest } = couponEvent;
    return json(res, 200, {
      ...rest,
      remainingQuantity: Math.max(0, remaining),
      state,
      myIssue: mine ? { couponIssueId: mine.couponIssueId, state: couponView(mine).state } : null,
    });
  }

  const m = /^\/api\/coupons\/events\/(\d+)\/claim$/.exec(path);
  if (m && req.method === "POST") {
    const key = req.headers["idempotency-key"];
    if (!key) return fail(res, 400, "VALIDATION_FAILED", "Idempotency-Key required", [{ field: "Idempotency-Key" }]);
    if (couponIdem.has(key)) {
      const first = couponIdem.get(key);
      // 같은 키에 다른 이벤트면 재생하지 않는다 (계약 CPN-02)
      if (first.eventId !== m[1]) return fail(res, 409, "IDEMPOTENCY_KEY_CONFLICT", "key reused for another event");
      return first.status === 201 ? json(res, 201, first.body) : fail(res, first.status, first.body.code, "replay", first.body.details);
    }
    const reply = (status, body) => {
      couponIdem.set(key, { eventId: m[1], status, body });
      return status === 201 ? json(res, 201, body) : fail(res, status, body.code, body.code, body.details);
    };
    if (Number(m[1]) !== couponEvent.eventId) return fail(res, 404, "NOT_FOUND", "no event");
    if (DEV_USER.grade !== "VIP") return reply(403, { code: "COUPON_NOT_ELIGIBLE", details: [{ field: "grade", value: DEV_USER.grade }] });
    const state = couponState();
    if (state !== "OPEN") {
      return reply(409, { code: "COUPON_EVENT_NOT_OPEN", details: [{ field: "eventId", value: String(couponEvent.eventId), reason: state }] });
    }
    const mine = myCoupons.find((c) => c.eventId === couponEvent.eventId);
    if (mine) return reply(409, { code: "COUPON_ALREADY_CLAIMED", details: [{ field: "couponIssueId", value: mine.couponIssueId }] });
    if (couponEvent.remaining <= 0) return reply(409, { code: "COUPON_SOLD_OUT", details: [] });
    couponEvent.remaining -= 1;
    const issue = {
      couponIssueId: randomUUID(),
      eventId: couponEvent.eventId,
      title: couponEvent.title,
      discountRate: couponEvent.discountRate,
      state: "ISSUED",
      expiresAt: couponEvent.endsAt,
    };
    myCoupons.unshift(issue);
    return reply(201, issue);
  }
  return fail(res, 404, "NOT_FOUND", "no route");
}

// ---------- AI 챗봇 (ai.openapi.yaml, AI-01·AI-04) ----------

/** off: 라우트 없음(404) · down: ai 파드 없음(gateway 503) · 그 밖: 정상 */
const MOCK_AI = process.env.MOCK_AI ?? "on";

// ai presets.yaml 13개와 같은 목록 (화면 버튼 동작 확인용). "내 예매"는 프리셋이 아니라 입력창으로 묻는다
const AI_PRESETS = [
  { presetId: "today_after_time", title: "오늘 ○시 이후 회차", example: "오늘 20시 이후에 시작하는 영화 알려줘" },
  { presetId: "theater_schedule", title: "극장 편성표", example: "내일 건대입구 편성표 보여줘" },
  { presetId: "movie_theaters", title: "영화별 상영 극장·시간", example: "별이 지는 항구 어디서 몇 시에 해?" },
  { presetId: "most_remaining_today", title: "잔여석 많은 오늘 회차", example: "오늘 자리 많이 남은 회차 5개" },
  { presetId: "almost_sold_out_today", title: "매진 임박 회차", example: "오늘 곧 매진되는 회차 있어?" },
  { presetId: "movies_on_date", title: "날짜별 상영 영화", example: "토요일에 상영하는 영화 뭐 있어?" },
  { presetId: "screen_type_today", title: "상영관 종류별 회차", example: "오늘 리클라이너관 회차 몇 개야?" },
  { presetId: "movie_next_screening", title: "영화 다음 회차", example: "별이 지는 항구 다음 회차 언제야?" },
  { presetId: "late_night_today", title: "심야 회차", example: "오늘 심야 영화 뭐 있어?" },
  { presetId: "morning_today", title: "조조 회차", example: "조조 영화 뭐 있어?" },
  { presetId: "movie_runtime", title: "영화 상영 시간", example: "별이 지는 항구 몇 분짜리야?" },
  { presetId: "movies_by_view_grade", title: "관람 등급별 영화", example: "전체 관람가 영화 알려줘" },
  { presetId: "theater_counts_today", title: "극장별 오늘 회차 수", example: "오늘 극장별로 남은 회차 몇 개야?" },
];

/**
 * 채팅으로 좌석 잡기 흉내 (초안 3.4.19, ai.openapi.yaml HOLD_*). 실제 선점은 없다 — 화면 흐름만.
 * "오늘 20시 J7 J8 잡아줘" → 확인 요청(버튼) → 「좌석 잡기」 → 결과 카드(결제 화면 /bookings/7xxx/pay 는 아래 가짜 예매)
 * 버튼 message 에 확인 ID가 실린다(`좌석 잡기 #c7f2a1`) — ID가 맞을 때만 잡고, 타이핑한 「좌석 잡기」는 확인이 아니다.
 * 좌석 이름에 Z 가 있으면 「없는 좌석」, X 가 있으면 「이미 잡힌 좌석」 안내(좌석 화면 링크). MOCK_AI_HOLD=off 면 이 흐름을 끈다
 */
const MOCK_AI_HOLD = process.env.MOCK_AI_HOLD !== "off";
/** 확인 버튼을 누를 수 있는 시간(초). 만료 화면을 빨리 보려면 MOCK_AI_HOLD_CONFIRM_SECONDS=10 */
const MOCK_AI_HOLD_CONFIRM_SECONDS = Number(process.env.MOCK_AI_HOLD_CONFIRM_SECONDS ?? 120);
const HOLD_CONFIRM_MESSAGE = "좌석 잡기";
const HOLD_CANCEL_MESSAGE = "취소";
/** sessionId → 확인을 기다리는 행동 (2분, 한 번 쓰면 지운다) */
const aiPendingHolds = new Map();
let aiHoldBookingId = 7100;
const seatHold = (over = {}) => ({ screeningId: null, bookingId: null, seatLabels: [], amount: null, expiresAt: null, confirmExpiresAt: null, reason: null, ...over });
const HOLD_REPLY = /^(좌석 잡기|취소)(?:\s*#([0-9a-f]{6}))?$/;

function aiHoldAnswer(message, sessionId, base) {
  const free = { llm: { calls: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 }, latencyMs: 180 };
  const reply = HOLD_REPLY.exec(message);
  const none = (answer) => ({ ...base, ...free, path: "HOLD_GUIDE", choices: null, seatHold: seatHold({ reason: "NOT_CONFIRMED" }), answer });
  // 버튼 없이 타이핑한 「좌석 잡기」는 확인이 아니다 — 대기 행동은 그대로 둔다
  if (reply && reply[1] === HOLD_CONFIRM_MESSAGE && !reply[2]) {
    return none("좌석을 잡으려면 확인 요청의 「잡기」 버튼을 눌러 주세요. 확인 요청이 없으면 잡을 회차와 좌석을 다시 말해 주세요.");
  }
  const pending = aiPendingHolds.get(sessionId);
  aiPendingHolds.delete(sessionId); // 무엇이 오든 대기 행동은 한 번만
  const alive = pending && pending.until > Date.now() ? pending : null;
  if (reply) {
    if (!alive) return none("확인할 좌석 요청이 없어요(확인 시간이 지났을 수 있어요). 잡을 회차와 좌석을 다시 말해 주세요.");
    if (reply[1] === HOLD_CANCEL_MESSAGE) {
      return { ...base, ...free, path: "HOLD_GUIDE", choices: null, seatHold: seatHold({ screeningId: alive.screeningId, reason: "CANCELED" }), answer: "좌석을 잡지 않았습니다." };
    }
    // 확인 ID가 다르면(지난 확인 요청의 버튼) 잡지 않는다
    if (reply[2] !== alive.confirmId) return none("지난 확인 요청의 버튼이에요. 좌석을 잡지 않았습니다. 잡을 회차와 좌석을 다시 말해 주세요.");
    const expiresAt = toKstIso(new Date(Date.now() + 5 * 60000));
    return {
      ...base,
      ...free,
      path: "HOLD_RESULT",
      choices: null,
      answer: `${alive.seatLabels.join("·")} 좌석을 잡았습니다. ${expiresAt.slice(11, 16)}까지 결제를 마쳐 주세요.`,
      seatHold: seatHold({ screeningId: alive.screeningId, bookingId: aiHoldBookingId++, seatLabels: alive.seatLabels, amount: alive.seatLabels.length * 15000, expiresAt }),
    };
  }
  const seatLabels = [...new Set((message.match(/(?<![A-Za-z])[A-Za-z]\s?\d{1,2}(?!\d)/g) ?? []).map((s) => s.replace(/\s/g, "").toUpperCase()))];
  if (seatLabels.length === 0 || !/잡아|예매해|예약해|선점/.test(message)) return null;
  const s = upcoming(todayIso()).find((x) => new Date(x.startsAt).getTime() > Date.now() + 10 * 60000) ?? upcoming(dateOffset(1))[0];
  const guide = (reason, answer) => ({ ...base, path: "HOLD_GUIDE", choices: null, answer, seatHold: seatHold({ screeningId: s.screeningId, seatLabels, reason }) });
  if (seatLabels.length > 4) return { ...guide("TOO_MANY_SEATS", "한 번에 최대 4석까지 잡을 수 있어요. 좌석을 줄여서 다시 말해 주세요."), seatHold: seatHold({ reason: "TOO_MANY_SEATS" }) };
  const missing = seatLabels.filter((l) => l.startsWith("Z"));
  if (missing.length) return guide("SEAT_NOT_FOUND", `${missing.join("·")} 좌석은 이 상영관에 없어요. 좌석 화면에서 직접 골라 주세요.`);
  const taken = seatLabels.filter((l) => l.startsWith("X"));
  if (taken.length) return guide("SEAT_UNAVAILABLE", `${taken.join("·")} 좌석은 이미 다른 분이 잡았어요. 좌석 화면에서 직접 골라 주세요.`);
  const confirmId = randomUUID().replace(/-/g, "").slice(0, 6);
  const until = Date.now() + MOCK_AI_HOLD_CONFIRM_SECONDS * 1000;
  aiPendingHolds.set(sessionId, { screeningId: s.screeningId, seatLabels, confirmId, until });
  return {
    ...base,
    path: "HOLD_CONFIRM",
    answer: `${s.movie.title} ${kstMinute(s.startsAt).slice(5).replace("-", "/")} ${s.theater.name} ${s.auditorium.name} ${seatLabels.join("·")} — ${seatLabels.length}석. 이 좌석을 5분간 잡을까요? (2분 안에 눌러 주세요)`,
    choices: [
      { label: `${seatLabels.join("·")} 잡기`, message: `${HOLD_CONFIRM_MESSAGE} #${confirmId}` },
      { label: HOLD_CANCEL_MESSAGE, message: `${HOLD_CANCEL_MESSAGE} #${confirmId}` },
    ],
    seatHold: seatHold({ screeningId: s.screeningId, seatLabels, confirmExpiresAt: toKstIso(new Date(until)) }),
  };
}

/**
 * 캐시 흉내 (3.4.20 데모 — 처음 → 같은 질문 → 다른 말투). 실제 ai 처럼 질문의 「틀」(시각 숫자를 뺀 모양)이 항목이다.
 *   처음 묻기                         → PRESET·NL2SQL + cache {stored: true}
 *   같은 질문·시각만 다른 질문          → CACHE_EXACT + 같은 entryId {stored: false}
 *   말투만 다른 질문("넘어서 하는 … 보여줘") → CACHE_SEMANTIC + 같은 entryId + similarity
 * 틀 → entryId. 서버가 켜질 때 미리 넣어 둔 항목(AI_WARMED)은 같은 대화에 짝이 없는 캐시 답이 된다
 */
const aiAsked = new Map();
/** 뜻 묶음(말투를 지운 틀) → entryId : 의미 캐시 흉내 */
const aiMeanings = new Map();
const aiTemplate = (message) =>
  message
    .toLowerCase()
    .replace(/\d{1,2}\s*시/g, "{time}")
    .replace(/[?!.,~\s]+/g, " ")
    .trim();
const aiMeaning = (template) =>
  template
    .replace(/넘어서|부터|이후에/g, "이후")
    .replace(/시작하는|하는|알려\s*줘|보여\s*줘|뭐\s*있어|있어|목록|좀|요$/g, " ")
    .replace(/\s+/g, " ")
    .trim();
const aiEntryId = (template) => createHash("sha256").update(template).digest("hex").slice(0, 12);
/** 유사도 흉내: 항목마다 고정된 0.93~0.99, 소수 둘째 자리 (실제 기준은 0.92 이상) */
const aiSimilarity = (template) => (93 + (parseInt(aiEntryId(template).slice(0, 4), 16) % 7)) / 100;
const AI_WARMED = ["오늘 심야 영화 뭐 있어?", "조조 영화 뭐 있어?"];
let aiInflight = false;

const kstMinute = (iso) => iso.slice(0, 16).replace("T", " ");
const nowKstIso = () => toKstIso(new Date());
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const hhmm = (iso) => {
  const [h, m] = iso.slice(11, 16).split(":");
  return `${Number(h)}시 ${Number(m)}분`;
};

const SCREENING_COLUMNS = [
  { name: "movie_title", type: "text" },
  { name: "theater_name", type: "text" },
  { name: "auditorium_name", type: "text" },
  { name: "starts_at", type: "text" },
  { name: "remaining_seats", type: "integer" },
];
const screeningRow = (s) => [s.movie.title, s.theater.name, s.auditorium.name, kstMinute(s.startsAt), s.remainingSeats];
const upcoming = (dateIso) => screeningsOn(dateIso).filter((s) => new Date(s.startsAt).getTime() > Date.now());

function aiAnswer(message, sessionId) {
  const asOf = nowKstIso();
  const base = {
    sessionId,
    columns: null,
    rows: null,
    screeningIds: null,
    rowCount: 0,
    truncated: false,
    sql: null,
    presetId: null,
    asOf,
    llm: { calls: 1, inputTokens: 412, outputTokens: 58, cacheReadTokens: 3810, cacheWriteTokens: 0 },
    latencyMs: 1860,
    rejection: null,
    choices: null,
    seatHold: null,
  };
  const at = `(${hhmm(asOf)} 기준)`;
  const table = (columns, rows) => ({ columns, rows, rowCount: rows.length });
  /** 회차 한 줄인 표: screeningIds(rows 와 같은 순서)로 화면이 좌석 선택 링크를 만든다 */
  const screeningTable = (columns, screenings, toRow) => ({
    ...table(columns, screenings.map(toRow)),
    screeningIds: screenings.length > 0 ? screenings.map((s) => s.screeningId) : null,
  });

  if (MOCK_AI_HOLD) {
    const hold = aiHoldAnswer(message, sessionId, base);
    if (hold) return hold;
  }

  if (/내\s*예매|예매\s*내역/.test(message)) {
    const mine = bookings.filter((b) => b.status === "CONFIRMED" && b.screening).slice(0, 5);
    return {
      ...base,
      path: "MY_BOOKINGS",
      answer: `확정된 예매가 ${mine.length}건 있습니다. ${at}`,
      ...table(
        [
          { name: "booking_id", type: "integer" },
          { name: "status", type: "text" },
          { name: "movie_title", type: "text" },
          { name: "theater_name", type: "text" },
          { name: "starts_at", type: "text" },
          { name: "seat_labels", type: "text" },
          { name: "amount", type: "integer" },
        ],
        mine.map((b) => [b.bookingId, b.status, b.screening.movieTitle, b.screening.theaterName, kstMinute(b.screening.startsAt), b.seatLabels.join(", "), b.amount]),
      ),
      llm: { calls: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0 },
      latencyMs: 142,
    };
  }
  if (/날씨|주식|맛집/.test(message)) {
    return { ...base, path: "OUT_OF_SCOPE", answer: "영화·극장·회차·내 예매에 대해서만 답할 수 있어요.", latencyMs: 980, llm: { ...base.llm, outputTokens: 21 } };
  }
  if (/테이블|snapshot|스키마/.test(message)) {
    return {
      ...base,
      path: "REJECTED",
      answer: "그 질문은 지금 데이터로 답하기 어렵습니다. 영화·극장·회차에 대해 물어봐 주세요.",
      sql: "SELECT table_name, refreshed_at FROM snapshot_meta",
      llm: { calls: 2, inputTokens: 921, outputTokens: 164, cacheReadTokens: 7620, cacheWriteTokens: 0 },
      latencyMs: 4120,
      rejection: { reason: "FORBIDDEN_OBJECT", message: "허용되지 않은 테이블을 조회하는 SQL입니다.", object: "snapshot_meta" },
    };
  }
  const hour = /(\d{1,2})\s*시\s*(?:이후|넘어서|부터)/.exec(message);
  if (hour) {
    const h = Number(hour[1]);
    const rows = upcoming(todayIso()).filter((s) => Number(s.startsAt.slice(11, 13)) >= h);
    return {
      ...base,
      path: "PRESET",
      presetId: "today_after_time",
      answer: `오늘 ${h}시 이후 시작하는 회차는 ${rows.length}개입니다. ${at}`,
      ...screeningTable(SCREENING_COLUMNS, rows, screeningRow),
    };
  }
  const theater = theaters.find((t) => t.hasLayout && message.includes(t.name));
  if (theater) {
    const date = message.includes("내일") ? dateOffset(1) : todayIso();
    const rows = (date === todayIso() ? upcoming(date) : screeningsOn(date)).filter((s) => s.theater.theaterId === theater.theaterId);
    return {
      ...base,
      path: "PRESET",
      presetId: "theater_schedule",
      answer: `${Number(date.slice(5, 7))}월 ${Number(date.slice(8))}일 ${theater.name} 회차는 ${rows.length}개입니다. ${at}`,
      ...screeningTable(
        [
          { name: "movie_title", type: "text" },
          { name: "auditorium_name", type: "text" },
          { name: "screen_type", type: "text" },
          { name: "starts_at", type: "text" },
          { name: "ends_at", type: "text" },
          { name: "remaining_seats", type: "integer" },
        ],
        rows,
        (s) => [s.movie.title, s.auditorium.name, s.auditorium.screenType, kstMinute(s.startsAt), kstMinute(s.endsAt), s.remainingSeats],
      ),
    };
  }
  const movie = movies.find((m) => message.includes(m.title));
  if (movie) {
    const rows = upcoming(todayIso()).filter((s) => s.movie.movieId === movie.movieId);
    return {
      ...base,
      path: "PRESET",
      presetId: "movie_theaters",
      answer: `오늘 ${movie.title} 남은 회차는 ${rows.length}개입니다. ${at}`,
      ...screeningTable(SCREENING_COLUMNS, rows, screeningRow),
    };
  }
  if (/비율|매진/.test(message)) {
    const rows = upcoming(todayIso()).filter((s) => s.totalSeats > 0 && s.remainingSeats / s.totalSeats <= 0.5);
    return {
      ...base,
      path: "NL2SQL",
      answer: `잔여석이 절반 이하인 오늘 회차는 ${rows.length}개입니다. ${at}`,
      ...screeningTable(
        [
          { name: "movie_title", type: "text" },
          { name: "theater_name", type: "text" },
          { name: "starts_at", type: "text" },
          { name: "remaining_seats", type: "integer" },
          { name: "total_seats", type: "integer" },
          { name: "remaining_ratio", type: "real" },
        ],
        rows,
        (s) => [s.movie.title, s.theater.name, kstMinute(s.startsAt), s.remainingSeats, s.totalSeats, Math.round((s.remainingSeats / s.totalSeats) * 1000) / 1000],
      ),
      sql: "SELECT m.title AS movie_title, t.name AS theater_name, s.starts_at, s.remaining_seats, s.total_seats, ROUND(1.0 * s.remaining_seats / s.total_seats, 3) AS remaining_ratio FROM screening s JOIN movie m ON m.movie_id = s.movie_id JOIN theater t ON t.theater_id = s.theater_id WHERE s.business_date = date('now') AND s.total_seats > 0 AND 1.0 * s.remaining_seats / s.total_seats <= 0.5 ORDER BY remaining_ratio LIMIT 500",
      llm: { calls: 1, inputTokens: 455, outputTokens: 212, cacheReadTokens: 3810, cacheWriteTokens: 0 },
      latencyMs: 3240,
    };
  }
  // 그 밖: 잔여석 많은 오늘 회차 5개 (프리셋 most_remaining_today)
  const rows = [...upcoming(todayIso())].sort((a, b) => b.remainingSeats - a.remainingSeats).slice(0, 5);
  return {
    ...base,
    path: "PRESET",
    presetId: "most_remaining_today",
    answer: `오늘 자리가 많이 남은 회차 ${rows.length}개입니다. ${at}`,
    ...screeningTable(SCREENING_COLUMNS, rows, screeningRow),
  };
}

for (const message of AI_WARMED) {
  const template = aiTemplate(message);
  aiAsked.set(template, aiEntryId(template));
  aiMeanings.set(aiMeaning(template), aiEntryId(template));
}

// ---------- 문맥 이어받기 (3.4.22) — ai 의 context·continueFrom 흉내 ----------
// 답마다 그 답이 조회한 날짜·극장·영화를 세션별로 5개까지 기억한다(ai 는 실행한 SQL의 조건에서 뽑는다).
// 영화 버튼이 continueFrom(그 답의 context.id)을 보내면 그 날짜·극장 + 고른 영화로만 답한다(Claude 호출 0).
// 6번째 전 답의 버튼·목 서버를 다시 띄운 뒤의 버튼은 모르는 id 라 독립 질문으로 답한다 → 화면의 「이어받지 못해…」 안내 확인용

const AI_CONTEXT_MAX = 5;
/** sessionId → [{ id, date, theater, movie, time, timeDirection }] (최신이 뒤) */
const aiContexts = new Map();
const WEEKDAYS_KO = "일월화수목금토";

const dayLabel = (dateIso) => {
  const offset = dayNumber(dateIso) - dayNumber(todayIso());
  const day = new Date(dayNumber(dateIso) * 86400000);
  const short = `${day.getUTCMonth() + 1}/${day.getUTCDate()}(${WEEKDAYS_KO[day.getUTCDay()]})`;
  return ["오늘", "내일", "모레"][offset] ? `${["오늘", "내일", "모레"][offset]} ${short}` : short;
};
/** "20:00" + after → "20시 이후" (ai context.time_label 과 같은 글자) */
const timeLabel = (time, direction) => {
  const [h, m] = time.split(":").map(Number);
  return `${m ? `${h}시 ${m}분` : `${h}시`} ${direction === "before" ? "이전" : "이후"}`;
};
/** 조건 표시 순서(ai 와 같다): 날짜 · 극장 · 시각 · 영화 */
const CONTEXT_ORDER = ["date", "theater", "time", "movie"];
const contextLabel = (values, kinds) =>
  CONTEXT_ORDER.filter((kind) => kinds.includes(kind) && values[kind])
    .map((kind) => (kind === "date" ? dayLabel(values.date) : kind === "time" ? timeLabel(values.time, values.timeDirection) : values[kind]))
    .join(" · ");
/** 목의 「○시 이후」는 시작 시각의 시(hour)로만 거른다(aiAnswer 의 today_after_time 갈래와 같은 기준) */
const startsAfter = (s, time) => Number(s.startsAt.slice(11, 13)) >= Number(time.slice(0, 2));

/** 목의 답이 조회한 조건 (aiAnswer 의 갈래와 같은 기준). 표가 없는 답·내 예매는 null */
function aiContextValues(answer, message) {
  if (!answer.columns || answer.path === "MY_BOOKINGS") return null;
  const values = { date: todayIso(), theater: null, movie: null, time: null, timeDirection: null };
  if (answer.presetId === "today_after_time") {
    // 「오늘 ○시 이후」: 시각 하한 하나 — ai 는 실행한 SQL의 starts_at >= '<오늘> HH:MM' 에서 읽는다
    const hour = /(\d{1,2})\s*시/.exec(message);
    if (hour) Object.assign(values, { time: `${hour[1].padStart(2, "0")}:00`, timeDirection: "after" });
  }
  if (answer.presetId === "theater_schedule") {
    values.theater = theaters.find((t) => t.hasLayout && message.includes(t.name))?.name ?? null;
    if (message.includes("내일")) values.date = dateOffset(1);
  }
  if (answer.presetId === "movie_theaters") values.movie = movies.find((m) => message.includes(m.title))?.title ?? null;
  return values;
}

/** 이번 답의 조건을 세션에 남기고 계약 `context` 모양으로 돌려준다 */
function rememberAiContext(sessionId, values, inherited = []) {
  if (!values) return null;
  // 시각은 날짜에 붙은 값이다 — 날짜가 없으면 두 칸 다 null
  const timed = values.date && values.time ? { time: values.time, timeDirection: values.timeDirection } : { time: null, timeDirection: null };
  const entry = { id: randomUUID().slice(0, 6), date: values.date, theater: values.theater, movie: values.movie, ...timed };
  aiContexts.set(sessionId, [...(aiContexts.get(sessionId) ?? []), entry].slice(-AI_CONTEXT_MAX));
  const label = contextLabel(entry, inherited.length > 0 ? inherited : CONTEXT_ORDER);
  return { ...entry, label, inherited };
}

/** ai 내부 틀(context_templates.yaml)에 값을 채운 모양의 SQL — 데모 정보의 「SQL 보기」용 */
function aiContinuedSql(values, asOf) {
  const quote = (text) => `'${String(text).replaceAll("'", "''")}'`;
  const where = [
    values.date && `s.business_date = ${quote(values.date)}`,
    values.theater && `t.name = ${quote(values.theater)}`,
    `m.title = ${quote(values.movie)}`,
    // 시각 틀: 연산자는 틀에 써 둔 것(이후 >=), 값은 바인딩
    values.time && `s.starts_at >= ${quote(values.date)} || ' ' || ${quote(values.time)}`,
    `s.starts_at > ${quote(kstMinute(asOf))}`,
  ].filter(Boolean);
  const columns = values.theater ? "s.starts_at" : "t.name AS theater_name, s.starts_at";
  return `SELECT ${columns} FROM screening AS s JOIN movie AS m ON m.movie_id = s.movie_id JOIN theater AS t ON t.theater_id = s.theater_id WHERE ${where.join(" AND ")} ORDER BY s.starts_at LIMIT 500`;
}

/** continueFrom 이 가리킨 답의 날짜·극장·시각 + 메시지의 영화로 답한다. 이어받을 것이 없으면 null(평소 답) */
function aiContinued(body, sessionId, message) {
  const from = (aiContexts.get(sessionId) ?? []).find((entry) => entry.id === body.continueFrom);
  const movie = movies.find((m) => message.includes(m.title));
  if (!from || !movie) return null;
  const date = from.date && dayNumber(from.date) >= dayNumber(todayIso()) ? from.date : null;
  const theater = theaters.find((t) => t.name === from.theater) ?? null;
  // 시각은 날짜를 이어받을 때만 같이 이어받는다(날짜가 지났으면 시각도 버린다)
  const time = date && from.time ? from.time : null;
  const inherited = [date && "date", theater && "theater", time && "time"].filter(Boolean);
  if (inherited.length === 0) return null;
  // 날짜가 없으면(극장만 이어받음) 오늘·내일 회차를 본다 — ai 는 그 극장의 남은 회차 전부
  const days = date ? [date] : [todayIso(), dateOffset(1)];
  const rows = days
    .flatMap((day) => (day === todayIso() ? upcoming(day) : screeningsOn(day)))
    .filter((s) => s.movie.movieId === movie.movieId && (!theater || s.theater.theaterId === theater.theaterId))
    .filter((s) => !time || startsAfter(s, time));
  const values = { date, theater: theater?.name ?? null, movie: movie.title, time, timeDirection: time ? "after" : null };
  // 표 열은 ai 내부 틀과 같다: 극장을 알면 시작 시각만, 날짜만 알면 극장·시작 시각 (상영관·종료·잔여석 없음)
  const columns = theater
    ? [{ name: "starts_at", type: "text" }]
    : [{ name: "theater_name", type: "text" }, { name: "starts_at", type: "text" }];
  const subject = `${contextLabel(values, CONTEXT_ORDER).replaceAll(" · ", " ")} 회차`;
  const count = rows.length > 0 ? `${rows.length}건입니다.` : "조건에 맞는 결과가 없습니다.";
  const asOf = nowKstIso();
  return {
    sessionId,
    answer: `${subject}: ${count} (${hhmm(asOf)} 기준)`,
    path: "CACHE_EXACT",
    columns,
    rows: rows.map((s) => (theater ? [kstMinute(s.startsAt)] : [s.theater.name, kstMinute(s.startsAt)])),
    // 행 하나가 회차 하나라 좌석 화면 링크도 붙는다(3.4.18)
    screeningIds: rows.length > 0 ? rows.map((s) => s.screeningId) : null,
    rowCount: rows.length,
    truncated: false,
    // 계약: 캐시 적중(CACHE_EXACT)이면 실행한 SQL과 그 캐시 항목(cache)이 있다
    sql: aiContinuedSql(values, asOf),
    cache: { entryId: aiEntryId(`ctx ${CONTEXT_ORDER.filter((kind) => values[kind]).join(" ")}`), stored: false, similarity: null },
    asOf,
    rejection: null,
    presetId: `${theater && date ? "ctx_movie_date_theater" : theater ? "ctx_movie_theater" : "ctx_movie_date"}${time ? "_after" : ""}`,
    llm: { calls: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0, cacheWriteTokens: 0 },
    latencyMs: 12,
    context: rememberAiContext(sessionId, values, inherited),
  };
}

async function handleAi(req, res, path) {
  if (MOCK_AI === "off") return fail(res, 404, "NOT_FOUND", path);
  if (MOCK_AI === "down") return fail(res, 503, "SERVICE_UNAVAILABLE", "no ai pod");

  if (path === "/api/ai/presets" && req.method === "GET") return json(res, 200, { items: AI_PRESETS });

  if (path === "/api/ai/chat" && req.method === "POST") {
    const body = await readJson(req);
    const message = typeof body?.message === "string" ? body.message.trim() : "";
    if (!message || body.message.length > 300) {
      return fail(res, 400, "VALIDATION_FAILED", "bad message", [{ field: "message", reason: "LENGTH" }]);
    }
    if (body.sessionId !== undefined && !/^[0-9a-f-]{36}$/i.test(String(body.sessionId))) {
      return fail(res, 400, "VALIDATION_FAILED", "bad sessionId", [{ field: "sessionId", reason: "FORMAT" }]);
    }
    if (message.includes("#409") || aiInflight) return fail(res, 409, "AI_QUERY_IN_PROGRESS", "in progress");
    if (message.includes("#429day")) {
      res.setHeader("Retry-After", "3600");
      return fail(res, 429, "AI_QUOTA_EXCEEDED", "daily", [{ field: "quota", value: "100/day", reason: "PER_DAY" }]);
    }
    if (message.includes("#429")) {
      res.setHeader("Retry-After", "15");
      return fail(res, 429, "AI_QUOTA_EXCEEDED", "per minute", [{ field: "quota", value: "10/min", reason: "PER_MINUTE" }]);
    }
    if (message.includes("#snapshot")) return fail(res, 503, "AI_SNAPSHOT_NOT_READY", "snapshot");
    if (message.includes("#503") || message.includes("#timeout")) {
      aiInflight = true;
      await sleep(message.includes("#timeout") ? 2500 : 600);
      aiInflight = false;
      const reason = message.includes("#timeout") ? "TIMEOUT" : "OVERLOADED";
      return fail(res, 503, "AI_LLM_UNAVAILABLE", reason, [{ field: "provider", value: "anthropic", reason }]);
    }

    if (typeof body.continueFrom === "string" && body.sessionId) {
      // 문맥 이어받기: 같은 문장의 캐시(aiAsked)는 보지도 남기지도 않는다 — 그 키는 조건 없는 답이다
      const continued = aiContinued(body, body.sessionId, message);
      if (continued) {
        await sleep(continued.latencyMs);
        return json(res, 200, continued);
      }
    }

    const sessionId = body.sessionId ?? randomUUID();
    const template = aiTemplate(message);
    const meaning = aiMeaning(template);
    let answer = { ...aiAnswer(message, sessionId), cache: null };
    const cacheable = answer.path === "PRESET" || answer.path === "NL2SQL";
    const free = { calls: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0 };
    if (cacheable && aiAsked.has(template)) {
      const cache = { entryId: aiAsked.get(template), stored: false, similarity: null };
      answer = { ...answer, path: "CACHE_EXACT", llm: free, latencyMs: 31, cache };
    } else if (cacheable && aiMeanings.has(meaning)) {
      const cache = { entryId: aiMeanings.get(meaning), stored: false, similarity: aiSimilarity(template) };
      answer = { ...answer, path: "CACHE_SEMANTIC", llm: free, latencyMs: 38, cache };
    } else if (cacheable) {
      const entryId = aiEntryId(template);
      aiAsked.set(template, entryId);
      aiMeanings.set(meaning, entryId);
      answer = { ...answer, cache: { entryId, stored: true, similarity: null } };
    }
    aiInflight = true;
    await sleep(Math.min(answer.latencyMs, 2500));
    aiInflight = false;
    return json(res, 200, { ...answer, context: rememberAiContext(sessionId, aiContextValues(answer, message)) });
  }
  return fail(res, 404, "NOT_FOUND", path);
}

// ---------- 서버 ----------


// ---------- 결제 화면 (BKG-10 · 토스페이 준비·완료, ADR-112) ----------
// /bookings/7001/pay … 7999 로 들어가면 그 번호의 PENDING 예매(선점 5분, J7·J8)가 처음 조회 때 만들어진다.
// 새 번호로 들어가면 새 예매다. 토스페이 결제창은 개발용 가짜 창이고, 고른 결과를 /api/mock-portone/{paymentId} 에 남긴다

const MOCK_STORE_ID = "store-mock";
const payBookings = new Map();
/** 가짜 포트원 쪽 결제 결과: paid · failed · canceled · abandoned(결제창만 닫고 기록은 준비 상태) */
const portoneResults = new Map();

function payBookingOf(id) {
  if (id < 7001 || id > 7999) return null;
  if (!payBookings.has(id)) {
    const soon = Date.now() + 60 * 60000;
    const pick =
      upcoming(todayIso()).find((s) => new Date(s.startsAt).getTime() > soon) ?? upcoming(dateOffset(1))[0];
    const now = new Date();
    payBookings.set(id, {
      bookingId: id,
      screeningId: pick.screeningId,
      status: "PENDING",
      seatIds: [100107, 100108],
      seatLabels: ["J7", "J8"],
      seatCount: 2,
      amount: 2 * SEAT_PRICE,
      expiresAt: toKstIso(new Date(now.getTime() + 300_000)),
      createdAt: toKstIso(now),
      confirmedAt: null,
      canceledAt: null,
      payment: null,
    });
  }
  return payBookings.get(id);
}

const payExpired = (b) => new Date(b.expiresAt).getTime() <= Date.now();

function settlePay(b, status, payment) {
  b.status = status;
  b.payment = payment;
  if (status === "CONFIRMED") b.confirmedAt = toKstIso(new Date());
  if (status !== "PENDING" && status !== "CONFIRMED") b.seatIds = [];
}

const payResult = (b) => ({
  bookingId: b.bookingId,
  status: b.status,
  ...(b.confirmedAt ? { confirmedAt: b.confirmedAt } : {}),
  seatLabels: b.seatLabels,
  payment: b.payment,
});

async function handlePayBooking(req, res, path) {
  let m;
  if ((m = /^\/api\/mock-portone\/([0-9a-f-]{36})$/.exec(path)) && req.method === "POST") {
    const body = await readJson(req);
    portoneResults.set(m[1], body?.result ?? "abandoned");
    return json(res, 200, { ok: true });
  }
  if (!(m = /^\/api\/bookings\/(\d+)(\/hold|\/payments|\/pay)?$/.exec(path))) return false;
  const b = payBookingOf(Number(m[1]));
  if (!b) return false;
  const action = m[2] ?? "";

  if (action === "" && req.method === "GET") return json(res, 200, b);

  if (action === "/hold" && req.method === "DELETE") {
    if (b.status === "CANCELED") return json(res, 200, { bookingId: b.bookingId, status: "CANCELED", canceledAt: b.canceledAt, payment: null });
    if (b.status !== "PENDING") return fail(res, 409, "BOOKING_STATE_CONFLICT", `status ${b.status}`, [{ field: "status", value: b.status, reason: "BOOKING_NOT_PENDING" }]);
    if (b.payment) return fail(res, 409, "BOOKING_STATE_CONFLICT", "payment in progress", [{ field: "bookingId", value: b.bookingId, reason: "PAYMENT_IN_PROGRESS" }]);
    settlePay(b, "CANCELED", null);
    b.canceledAt = toKstIso(new Date());
    return json(res, 200, { bookingId: b.bookingId, status: "CANCELED", canceledAt: b.canceledAt, payment: null });
  }

  if (action === "/payments" && req.method === "POST") {
    const body = await readJson(req);
    if (body?.method !== "TOSSPAY") return fail(res, 400, "VALIDATION_FAILED", "TOSSPAY only", [{ field: "method", value: body?.method ?? null, reason: "INVALID_PAYMENT_METHOD" }]);
    if (b.payment) return fail(res, 409, "PAYMENT_ALREADY_REQUESTED", "already requested", [{ field: "bookingId", value: b.bookingId, reason: "PAYMENT_ALREADY_REQUESTED" }]);
    if (b.status !== "PENDING") return fail(res, 409, "BOOKING_STATE_CONFLICT", `status ${b.status}`, [{ field: "status", value: b.status, reason: "BOOKING_NOT_PENDING" }]);
    if (payExpired(b)) return fail(res, 409, "BOOKING_STATE_CONFLICT", "hold expired", [{ field: "status", value: b.status, reason: "HOLD_EXPIRED" }]);
    const paymentId = randomUUID();
    b.payment = { paymentId, status: "PROCESSING", method: "TOSSPAY", cardLast4: null, failureCode: null, approvedAt: null, canceledAt: null };
    return json(res, 200, {
      bookingId: b.bookingId,
      paymentId,
      amount: b.amount,
      orderName: `SeatSAC 영화 예매 ${b.seatCount}석`,
      expiresAt: b.expiresAt,
      checkout: { provider: "PORTONE", storeId: MOCK_STORE_ID, channelKey: "channel-key-mock", payMethod: "EASY_PAY", currency: "KRW" },
    });
  }

  if (action === "/pay" && req.method === "POST") {
    const body = await readJson(req);
    const now = toKstIso(new Date());
    // 모의 결제(CARD)는 k6·분석 Job 전용(서버 키 헤더) — 화면에서는 부르지 않는다
    if (body?.method === "CARD") return fail(res, 400, "VALIDATION_FAILED", "mock card is for tests only", [{ field: "method", value: "CARD", reason: "MOCK_NOT_ALLOWED" }]);
    if (body?.method !== "TOSSPAY") return fail(res, 400, "VALIDATION_FAILED", "bad method", [{ field: "method", reason: "INVALID_PAYMENT_METHOD" }]);
    if (!b.payment || body.paymentId !== b.payment.paymentId) {
      return fail(res, 409, "PAYMENT_ALREADY_REQUESTED", "payment id mismatch", [{ field: "paymentId", value: body.paymentId ?? null, reason: "PAYMENT_ID_MISMATCH" }]);
    }
    if (b.status === "CONFIRMED" || b.status === "PAYMENT_FAILED") return json(res, 200, payResult(b));
    if (b.status !== "PENDING") return fail(res, 409, "BOOKING_STATE_CONFLICT", `status ${b.status}`, [{ field: "status", value: b.status, reason: "HOLD_EXPIRED" }, { field: "refunded", value: b.payment.status === "CANCELED" }]);
    await sleep(600);
    const result = portoneResults.get(b.payment.paymentId) ?? "abandoned";
    if (result === "abandoned") return fail(res, 503, "PAYMENT_RESULT_UNKNOWN", "window not completed", [{ field: "paymentId", value: b.payment.paymentId }]);
    if (result === "paid") {
      if (payExpired(b)) {
        settlePay(b, "EXPIRED", { ...b.payment, status: "CANCELED", approvedAt: now, canceledAt: now });
        return fail(res, 409, "BOOKING_STATE_CONFLICT", "hold expired", [{ field: "status", value: "EXPIRED", reason: "HOLD_EXPIRED" }, { field: "refunded", value: true }]);
      }
      settlePay(b, "CONFIRMED", { ...b.payment, status: "APPROVED", approvedAt: now });
      return json(res, 200, payResult(b));
    }
    settlePay(b, "PAYMENT_FAILED", { ...b.payment, status: "FAILED", failureCode: result === "canceled" ? "USER_CANCELED" : "PG_DECLINED" });
    return json(res, 200, payResult(b));
  }
  return false;
}

createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", `http://localhost:${PORT}`);
  const path = url.pathname;
  const q = url.searchParams;
  console.log(new Date().toISOString(), req.method, req.url);

  let m;
  if ((m = /^\/mock-poster\/(\d+)\.svg$/.exec(path))) {
    const movie = movies.find((x) => x.movieId === Number(m[1]));
    const hue = (Number(m[1]) * 53) % 360;
    const title = (movie?.title ?? "").replace(/[<&]/g, "");
    res.writeHead(200, { "Content-Type": "image/svg+xml", "Cache-Control": "no-store" });
    res.end(
      `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="600"><rect width="400" height="600" fill="hsl(${hue} 40% 35%)"/><text x="200" y="300" fill="#fff" font-size="28" font-family="sans-serif" text-anchor="middle">${title}</text><text x="200" y="560" fill="#ddd" font-size="16" font-family="sans-serif" text-anchor="middle">mock poster</text></svg>`,
    );
    return;
  }

  const failStatus = FAIL_PATH && path === FAIL_PATH ? FAIL_STATUS : Number(q.get("fail") ?? 0);
  if (failStatus === 503) {
    // gateway GatewayErrorHandler와 같은 모양: 멈춘 서비스의 라우트 ID를 details에 담는다 (화면이 서비스별 안내를 고른다)
    const routeId = gatewayRouteIdOf(path);
    const details = routeId ? [{ field: "service", value: routeId, reason: "UPSTREAM_UNAVAILABLE" }] : [];
    return fail(res, 503, "SERVICE_UNAVAILABLE", "simulated by mock", details);
  }
  if (failStatus === 500) return fail(res, 500, "INTERNAL_ERROR", "simulated by mock");

  // 인증이 필요한 경로: Authorization: Bearer 가 있으면 누구든 통과 (Gateway 의 JWT 검증은 흉내 내지 않는다)
  if (requiresAuth(path) && !/^Bearer\s+\S+/.test(req.headers.authorization ?? "")) {
    return fail(res, 401, "UNAUTHORIZED", "missing bearer token");
  }

  if (path.startsWith("/api/ai/")) return handleAi(req, res, path);
  if (path.startsWith("/api/coupons/")) return handleCoupons(req, res, path);

  if (path.startsWith("/api/mock-portone/") || /^\/api\/bookings\/7\d{3}(\/|$)/.test(path)) {
    if ((await handlePayBooking(req, res, path)) !== false) return;
  }

  if (path === "/api/users/me" && req.method === "GET") return json(res, 200, DEV_USER);

  if (path === "/api/users/me" && req.method === "PATCH") {
    const body = await readJson(req);
    const name = body?.name;
    if (typeof name !== "string" || name.trim().length < 1 || name.length > 20) {
      return fail(res, 400, "VALIDATION_FAILED", "bad name", [{ field: "name", reason: "LENGTH_1_20" }]);
    }
    DEV_USER.name = name.trim();
    return json(res, 200, DEV_USER);
  }

  if (path === "/api/users/me/bookings") {
    const status = q.get("status");
    const pageRaw = q.get("page") ?? "0";
    const sizeRaw = q.get("size") ?? "20";
    const details = [];
    if (status !== null && !BOOKING_STATUSES.includes(status)) details.push({ field: "status", reason: "ENUM" });
    if (!isNonNegativeInt(pageRaw)) details.push({ field: "page", reason: "MIN_0_INTEGER" });
    if (!isNonNegativeInt(sizeRaw) || Number(sizeRaw) < 1 || Number(sizeRaw) > 50) details.push({ field: "size", reason: "RANGE_1_50_INTEGER" });
    if (details.length > 0) return fail(res, 400, "VALIDATION_FAILED", "bad query", details);
    const page = Number(pageRaw);
    const size = Number(sizeRaw);
    const all = status ? bookings.filter((b) => b.status === status) : bookings;
    return json(res, 200, {
      content: all.slice(page * size, page * size + size),
      page,
      size,
      totalElements: all.length,
      totalPages: Math.ceil(all.length / size),
    });
  }

  if (path === "/api/users/me/preferences" && req.method === "GET") return json(res, 200, preferences);

  if (path === "/api/users/me/preferences" && req.method === "PUT") {
    const body = await readJson(req);
    const details = validatePreferences(body);
    if (details.length > 0) return fail(res, 400, "VALIDATION_FAILED", "bad preferences", details);
    preferences = {
      preferredTheaters: [...body.preferredTheaters]
        .sort((a, b) => a.rank - b.rank)
        .map(({ rank, theaterId }) => ({ rank, theaterId })),
      preferredTimeSlot: body.preferredTimeSlot,
      preferredGenres: [...body.preferredGenres],
    };
    return json(res, 200, preferences);
  }

  if ((m = /^\/api\/bookings\/(\d+)\/cancel$/.exec(path)) && req.method === "POST") {
    const booking = bookings.find((b) => b.bookingId === Number(m[1]));
    if (!booking) return fail(res, 404, "NOT_FOUND", `booking ${m[1]}`);
    // 이미 취소된 예매의 재시도(응답 유실 뒤 같은 Idempotency-Key)는 실제 booking 처럼 같은 결과를 200 으로 다시 준다
    if (booking.status === "CANCELED") {
      return json(res, 200, {
        bookingId: booking.bookingId,
        status: "CANCELED",
        canceledAt: booking.canceledAt,
        payment: { paymentId: booking.paymentId, status: "CANCELED" },
      });
    }
    if (booking.status !== "CONFIRMED") return fail(res, 409, "BOOKING_STATE_CONFLICT", `status ${booking.status}`);
    if (booking.screening && new Date(booking.screening.startsAt).getTime() <= Date.now()) {
      return fail(res, 400, "SCREENING_STARTED", "screening already started");
    }
    booking.status = "CANCELED";
    booking.canceledAt = toKstIso(new Date());
    booking.paymentId = randomUUID();
    return json(res, 200, {
      bookingId: booking.bookingId,
      status: "CANCELED",
      canceledAt: booking.canceledAt,
      payment: { paymentId: booking.paymentId, status: "CANCELED" },
    });
  }

  if (path === "/api/movies") {
    const status = q.get("status") ?? "NOW_SHOWING";
    const pageRaw = q.get("page") ?? "0";
    const sizeRaw = q.get("size") ?? "20";
    const details = [];
    if (!["NOW_SHOWING", "COMING_SOON"].includes(status)) details.push({ field: "status", reason: "ENUM" });
    if (!isNonNegativeInt(pageRaw)) details.push({ field: "page", reason: "MIN_0_INTEGER" });
    if (!isNonNegativeInt(sizeRaw) || Number(sizeRaw) < 1 || Number(sizeRaw) > 50) details.push({ field: "size", reason: "RANGE_1_50_INTEGER" });
    if (details.length > 0) return fail(res, 400, "VALIDATION_FAILED", "bad query", details);
    const page = Number(pageRaw);
    const size = Number(sizeRaw);
    const all = status === "NOW_SHOWING" ? movies.map(movieSummary) : [];
    return json(res, 200, {
      content: all.slice(page * size, page * size + size),
      page,
      size,
      totalElements: all.length,
      totalPages: Math.ceil(all.length / size),
    });
  }

  if ((m = /^\/api\/movies\/(\d+)$/.exec(path))) {
    const movie = movies.find((x) => x.movieId === Number(m[1]));
    if (!movie) return fail(res, 404, "NOT_FOUND", `movie ${m[1]}`);
    return json(res, 200, movieDetail(movie));
  }

  if (path === "/api/theaters") {
    const rawHasLayout = q.get("hasLayout");
    if (rawHasLayout !== null && rawHasLayout !== "true" && rawHasLayout !== "false") {
      return fail(res, 400, "VALIDATION_FAILED", "hasLayout must be boolean", [{ field: "hasLayout", reason: "BOOLEAN" }]);
    }
    const hasLayout = rawHasLayout ?? "true";
    return json(res, 200, { items: theaters.filter((t) => hasLayout === "false" || t.hasLayout) });
  }

  if (path === "/api/screenings") {
    const date = q.get("date");
    const theaterRaw = q.get("theaterId");
    const movieRaw = q.get("movieId");
    const details = [];
    if (!isIsoDate(date)) details.push({ field: "date", reason: date ? "DATE" : "REQUIRED" });
    if (!isPositiveIntId(theaterRaw)) details.push({ field: "theaterId", reason: theaterRaw ? "INTEGER" : "REQUIRED" });
    // 선택 파라미터라도 "있으면" 양의 정수여야 한다 (?movieId= 빈 값도 400)
    if (movieRaw !== null && !isPositiveIntId(movieRaw)) details.push({ field: "movieId", reason: "INTEGER" });
    if (details.length > 0) return fail(res, 400, "VALIDATION_FAILED", "bad query", details);
    const theaterId = Number(theaterRaw);
    const movieId = movieRaw ? Number(movieRaw) : null;
    if (!inSeedRange(date)) return json(res, 200, { items: [] });
    const now = Date.now();
    const isToday = date === todayIso();
    const items = screeningsOn(date)
      .filter((s) => s.theater.theaterId === theaterId)
      .filter((s) => movieId === null || s.movie.movieId === movieId)
      .filter((s) => !isToday || new Date(s.startsAt).getTime() > now);
    return json(res, 200, { items });
  }

  if ((m = /^\/api\/screenings\/(\d+)$/.exec(path))) {
    const hit = findScreening(Number(m[1]));
    if (!hit) return fail(res, 404, "NOT_FOUND", `screening ${m[1]}`);
    return json(res, 200, hit);
  }

  return fail(res, 404, "NOT_FOUND", path);
}).listen(PORT, () => {
  console.log(`mock catalog·user on http://localhost:${PORT}  (movies ${movies.length}, theaters ${theaters.filter((t) => t.hasLayout).length}, bookings ${bookings.length})`);
  console.log(`Vite 와 함께: npm run dev:mock  /  따로: DEV_API_PROXY=http://localhost:${PORT} npm run dev`);
});
