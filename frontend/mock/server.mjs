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
//        MOCK_ROLE=ADMIN (프로필 role) · 예매 ID 9001 은 회차 정보 없음(screening null)
// AI 챗봇(ai.openapi.yaml): MOCK_AI=off → /api/ai/** 404(gateway 플래그 off, 버튼 숨김) · MOCK_AI=down → 503(ai 파드 없음)
//        질문에 #409 · #429 · #429day · #503 · #timeout · #snapshot 을 넣으면 그 오류로 응답
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

const PORT = Number(process.env.MOCK_PORT ?? 8089);
/** 화면에서 오류 상태를 재현할 때: 이 경로는 항상 MOCK_FAIL_STATUS(기본 503)로 응답 (예: MOCK_FAIL_PATH=/api/screenings) */
const FAIL_PATH = process.env.MOCK_FAIL_PATH ?? "";
const FAIL_STATUS = Number(process.env.MOCK_FAIL_STATUS ?? 503);
/** GET /api/users/me 의 role (USER | ADMIN) */
const MOCK_ROLE = process.env.MOCK_ROLE === "ADMIN" ? "ADMIN" : "USER";
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
  role: MOCK_ROLE,
  createdAt: "2026-10-28T09:00:00+09:00",
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
const summaryOf = (s) => ({
  screeningId: s.screeningId,
  movieTitle: s.movie.title,
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
  path.startsWith("/api/users/") || path.startsWith("/api/bookings/") || path.startsWith("/api/ai/");

// ---------- AI 챗봇 (ai.openapi.yaml, AI-01·AI-04) ----------

/** off: 라우트 없음(404) · down: ai 파드 없음(gateway 503) · 그 밖: 정상 */
const MOCK_AI = process.env.MOCK_AI ?? "on";

const AI_PRESETS = [
  { presetId: "today_after_time", title: "오늘 ○시 이후 회차", example: "오늘 20시 이후에 시작하는 영화 알려줘" },
  { presetId: "theater_schedule", title: "극장 편성표", example: "내일 건대입구 편성표 보여줘" },
  { presetId: "movie_theaters", title: "영화별 상영 극장·시간", example: "별이 지는 항구 어디서 몇 시에 해?" },
  { presetId: "most_remaining_today", title: "잔여석 많은 오늘 회차", example: "오늘 자리 많이 남은 회차 5개" },
  { presetId: "my_bookings", title: "내 예매", example: "내 예매 보여줘" },
];

/** 정확 일치 캐시 흉내: 같은 문장을 다시 물으면 CACHE_EXACT(토큰 0) */
const aiAsked = new Map();
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
    rowCount: 0,
    truncated: false,
    sql: null,
    presetId: null,
    asOf,
    llm: { calls: 1, inputTokens: 412, outputTokens: 58, cacheReadTokens: 3810, cacheWriteTokens: 0 },
    latencyMs: 1860,
    rejection: null,
  };
  const at = `(${hhmm(asOf)} 기준)`;
  const table = (columns, rows) => ({ columns, rows, rowCount: rows.length });

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
  const hour = /(\d{1,2})\s*시\s*이후/.exec(message);
  if (hour) {
    const h = Number(hour[1]);
    const rows = upcoming(todayIso()).filter((s) => Number(s.startsAt.slice(11, 13)) >= h);
    return {
      ...base,
      path: "PRESET",
      presetId: "today_after_time",
      answer: `오늘 ${h}시 이후 시작하는 회차는 ${rows.length}개입니다. ${at}`,
      ...table(SCREENING_COLUMNS, rows.map(screeningRow)),
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
      ...table(
        [
          { name: "movie_title", type: "text" },
          { name: "auditorium_name", type: "text" },
          { name: "screen_type", type: "text" },
          { name: "starts_at", type: "text" },
          { name: "ends_at", type: "text" },
          { name: "remaining_seats", type: "integer" },
        ],
        rows.map((s) => [s.movie.title, s.auditorium.name, s.auditorium.screenType, kstMinute(s.startsAt), kstMinute(s.endsAt), s.remainingSeats]),
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
      ...table(SCREENING_COLUMNS, rows.map(screeningRow)),
    };
  }
  if (/비율|매진/.test(message)) {
    const rows = upcoming(todayIso())
      .filter((s) => s.totalSeats > 0 && s.remainingSeats / s.totalSeats <= 0.5)
      .map((s) => [s.movie.title, s.theater.name, kstMinute(s.startsAt), s.remainingSeats, s.totalSeats, Math.round((s.remainingSeats / s.totalSeats) * 1000) / 1000]);
    return {
      ...base,
      path: "NL2SQL",
      answer: `잔여석이 절반 이하인 오늘 회차는 ${rows.length}개입니다. ${at}`,
      ...table(
        [
          { name: "movie_title", type: "text" },
          { name: "theater_name", type: "text" },
          { name: "starts_at", type: "text" },
          { name: "remaining_seats", type: "integer" },
          { name: "total_seats", type: "integer" },
          { name: "remaining_ratio", type: "real" },
        ],
        rows,
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
    ...table(SCREENING_COLUMNS, rows.map(screeningRow)),
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

    const sessionId = body.sessionId ?? randomUUID();
    const key = message.replace(/\s+/g, " ").toLowerCase();
    let answer = aiAnswer(message, sessionId);
    const personal = answer.path === "MY_BOOKINGS";
    const cacheable = answer.path === "PRESET" || answer.path === "NL2SQL";
    if (!personal && aiAsked.has(key)) {
      answer = { ...answer, path: "CACHE_EXACT", llm: { calls: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0 }, latencyMs: 31 };
    } else if (cacheable) {
      aiAsked.set(key, true);
    }
    aiInflight = true;
    await sleep(Math.min(answer.latencyMs, 2500));
    aiInflight = false;
    return json(res, 200, answer);
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
  if (failStatus === 503) return fail(res, 503, "SERVICE_UNAVAILABLE", "simulated by mock");
  if (failStatus === 500) return fail(res, 500, "INTERNAL_ERROR", "simulated by mock");

  // 인증이 필요한 경로: Authorization: Bearer 가 있으면 누구든 통과 (Gateway 의 JWT 검증은 흉내 내지 않는다)
  if (requiresAuth(path) && !/^Bearer\s+\S+/.test(req.headers.authorization ?? "")) {
    return fail(res, 401, "UNAUTHORIZED", "missing bearer token");
  }

  if (path.startsWith("/api/ai/")) return handleAi(req, res, path);

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
