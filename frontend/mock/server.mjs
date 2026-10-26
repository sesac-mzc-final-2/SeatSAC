// 로컬 화면 작업용 catalog 목 서버 — contracts/openapi/catalog.openapi.yaml 모양으로 응답한다.
// 의존성 없음(Node 내장 http). 데이터는 전부 이 파일 안의 가짜 값이다(롯데시네마 수집 데이터·포스터 없음).
//
//   npm run mock              # http://localhost:8089
//   npm run dev:mock          # 목 + Vite(/api → 8089) 한 번에
//   MOCK_PORT=9000 npm run mock
//
// 재현용: GET /api/screenings?...&fail=503 → 503 SERVICE_UNAVAILABLE, /api/movies/999 → 404
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

const PORT = Number(process.env.MOCK_PORT ?? 8089);
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
/** 계약의 integer(int64) ID: 양의 정수 문자열만 */
const isPositiveIntId = (s) => /^\d+$/.test(s ?? "") && Number(s) > 0;
const isNonNegativeInt = (s) => /^\d+$/.test(s ?? "");
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

// ---------- 서버 ----------

createServer((req, res) => {
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

  if (q.get("fail") === "503") return fail(res, 503, "SERVICE_UNAVAILABLE", "simulated by mock");
  if (q.get("fail") === "500") return fail(res, 500, "INTERNAL_ERROR", "simulated by mock");

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
    if (movieRaw !== null && movieRaw !== "" && !isPositiveIntId(movieRaw)) details.push({ field: "movieId", reason: "INTEGER" });
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
  console.log(`mock catalog on http://localhost:${PORT}  (movies ${movies.length}, theaters ${theaters.filter((t) => t.hasLayout).length})`);
  console.log(`Vite 와 함께: npm run dev:mock  /  따로: DEV_API_PROXY=http://localhost:${PORT} npm run dev`);
});
