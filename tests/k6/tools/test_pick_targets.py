"""pick_targets·sse_lag 단위 테스트 — 네트워크 없이 돈다(가짜 조회 API 서버).

    python3 -m unittest discover -s tests/k6/tools -v
"""
from __future__ import annotations

import io
import json
import sys
import threading
import unittest
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))

import pick_targets as pt  # noqa: E402
import sse_lag  # noqa: E402

KST = timezone(timedelta(hours=9))
NOW = datetime(2026, 11, 10, 3, 0, tzinfo=timezone.utc)   # 12:00 KST


def screening(sid: int, aud: int, starts_kst: str, total: int) -> dict:
    return {
        "screeningId": sid, "movie": {"movieId": 1, "title": "영화", "viewGrade": "ALL"},
        "theater": {"theaterId": 1004, "name": "건대입구"},
        "auditorium": {"auditoriumId": aud, "name": f"{aud % 100}관", "screenType": "2D"},
        "layoutVersion": 1, "businessDate": "2026-11-10", "startsAt": starts_kst, "endsAt": starts_kst,
        "totalSeats": total, "remainingSeats": total, "remainingSeatsSource": "LIVE",
    }


def seats(aud: int, n: int, taken: dict[int, str] | None = None, gap_at: int | None = None) -> list[dict]:
    """좌석 ID = auditorium_id × 10000 + 순번. gap_at 순번은 비활성(스냅샷에 없음)"""
    out = []
    for i in range(1, n + 1):
        if i == gap_at:
            continue
        sid = aud * 10000 + i
        out.append({"seatId": sid, "status": (taken or {}).get(sid, "AVAILABLE")})
    return out


class FakeApi(BaseHTTPRequestHandler):
    routes: dict = {}
    hits: list = []

    def do_GET(self):  # noqa: N802
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        FakeApi.hits.append((u.path, q))
        body = FakeApi.routes.get((u.path, q.get("theaterId")), FakeApi.routes.get((u.path, None)))
        if body is None:
            self.send_response(404); self.end_headers(); self.wfile.write(b'{"code":"NOT_FOUND"}'); return
        data = json.dumps(body).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):  # 조용히
        pass


class PickTargetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), FakeApi)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        FakeApi.hits = []
        FakeApi.routes = {
            ("/theaters", None): {"items": [
                {"theaterId": 1004, "name": "건대입구", "region": "서울", "hasLayout": True},
                {"theaterId": 2000, "name": "배치 없음", "region": None, "hasLayout": False},
            ]},
            ("/screenings", "1004"): {"items": [
                screening(1, 406, "2026-11-10T12:30:00+09:00", 250),   # 30분 뒤 — 마감에 가까워 제외
                screening(2, 406, "2026-11-10T15:00:00+09:00", 250),   # 큰 관 → deploy
                screening(3, 401, "2026-11-10T16:00:00+09:00", 120),   # 일부 선점됨 → contention 불가, user-hold 가능
                screening(4, 402, "2026-11-10T17:00:00+09:00", 100),   # 전부 빈 → contention
            ]},
            ("/screenings/2/seats", None): {"seats": seats(406, 250, gap_at=30)},
            ("/screenings/3/seats", None): {"seats": seats(401, 120, taken={4010001: "HELD"})},
            ("/screenings/4/seats", None): {"seats": seats(402, 100)},
        }

    def run_pick(self, deploy_seats=200):
        return pt.pick(pt.Api(self.base), "2026-11-10", None, NOW, timedelta(minutes=60), deploy_seats)

    def test_시나리오마다_다른_회차를_고른다(self):
        env = self.run_pick()["env"]
        self.assertEqual(env["DEPLOY_SCREENING_ID"], 2)
        self.assertEqual(env["CONTENTION_SCREENING_ID"], 4)
        self.assertEqual(env["USERHOLD_SCREENING_ID"], 3)
        self.assertEqual(len({env["DEPLOY_SCREENING_ID"], env["CONTENTION_SCREENING_ID"], env["USERHOLD_SCREENING_ID"]}), 3)
        self.assertEqual(env["THEATER_ID"], 1004)

    def test_마감에_가까운_회차와_배치_없는_극장은_쓰지_않는다(self):
        env = self.run_pick()["env"]
        self.assertNotIn("1", env["BROWSE_SCREENING_IDS"].split(","))
        self.assertFalse(any(q.get("theaterId") == "2000" for _, q in FakeApi.hits))

    def test_deploy는_비활성_좌석을_건너뛴_연속_구간을_쓴다(self):
        env = self.run_pick()["env"]
        # 순번 30이 비활성 → 31~250(220석)이 가장 긴 구간
        self.assertEqual(env["DEPLOY_SEAT_ID_FROM"], 4060031)

    def test_contention_좌석은_빈_좌석이고_user_hold는_서로_다른_빈_좌석_2개다(self):
        env = self.run_pick()["env"]
        self.assertTrue(4020001 <= env["CONTENTION_SEAT_ID"] <= 4020100)
        a, b = (int(x) for x in env["USERHOLD_SEAT_IDS"].split(","))
        self.assertNotEqual(a, b)
        self.assertNotIn(4010001, (a, b))   # 선점된 좌석은 고르지 않는다

    def test_연속_좌석이_모자라면_이유를_말하고_멈춘다(self):
        with self.assertRaisesRegex(pt.PickError, "연속 빈 좌석 500개"):
            self.run_pick(deploy_seats=500)

    def test_남은_회차가_없으면_멈춘다(self):
        with self.assertRaisesRegex(pt.PickError, "회차가 없다"):
            pt.pick(pt.Api(self.base), "2026-11-10", 1004, NOW + timedelta(hours=10), timedelta(minutes=60), 10)

    def test_main은_export_줄만_stdout에_낸다(self):
        out, err = io.StringIO(), io.StringIO()
        real_now = pt.datetime
        try:
            class FixedNow(datetime):
                @classmethod
                def now(cls, tz=None):
                    return NOW.astimezone(tz) if tz else NOW
            pt.datetime = FixedNow
            sys_out, sys_err = sys.stdout, sys.stderr
            sys.stdout, sys.stderr = out, err
            try:
                rc = pt.main(["--base-url", self.base, "--date", "2026-11-10"])
            finally:
                sys.stdout, sys.stderr = sys_out, sys_err
        finally:
            pt.datetime = real_now
        self.assertEqual(rc, 0, err.getvalue())
        lines = out.getvalue().strip().splitlines()
        self.assertTrue(lines and all(line.startswith("export ") for line in lines), lines)

    def test_longest_run(self):
        self.assertEqual(pt.longest_run([1, 2, 3, 5, 6]), (1, 3))
        self.assertEqual(pt.longest_run([]), (0, 0))
        self.assertEqual(pt.longest_run([7]), (7, 1))


class SseLagTest(unittest.TestCase):
    def test_이벤트_지연과_하트비트_간격을_잰다(self):
        base = datetime(2026, 11, 10, 3, 0, tzinfo=timezone.utc).timestamp()
        occurred = datetime.fromtimestamp(base, timezone.utc).astimezone(KST).isoformat(timespec="milliseconds")
        ev = json.dumps({"seq": 1, "type": "HELD", "screeningId": 4, "seatIds": [4020050],
                         "occurredAt": occurred, "traceId": "0" * 32})
        lines = ["retry: 2000\n", "\n", ": ping\n", "event: seat\n", "id: 1\n", f"data: {ev}\n", "\n", ": ping\n"]
        r = sse_lag.measure(lines, duration=60, clock=lambda: base + 0.25, out=io.StringIO())
        self.assertEqual(r["events"], 1)
        self.assertAlmostEqual(r["p95"], 0.25, places=3)
        self.assertEqual(r["pings"], 2)

    def test_줄을_받은_시각으로_하트비트_간격을_구한다(self):
        lines = [": ping\n", ": ping\n", "data: {broken\n", "\n"]
        times = iter([100.0, 115.0, 116.0, 116.5])
        got = list(sse_lag.parse_stream(lines, clock=lambda: next(times)))
        self.assertEqual([(k, t) for k, _, t in got], [("ping", 100.0), ("ping", 115.0)])   # 깨진 data는 버린다

    def test_연결_주석은_하트비트로_세지_않는다(self):
        # booking은 연결 직후 `: connected`를 보내고 하트비트 `: ping`은 모든 연결에 같은 15초 주기로 보낸다
        lines = [": connected\n", ": ping\n", ": ping\n"]
        times = iter([100.0, 103.0, 118.0])
        got = list(sse_lag.parse_stream(lines, clock=lambda: next(times)))
        self.assertEqual([(k, t) for k, _, t in got], [("ping", 103.0), ("ping", 118.0)])   # 간격 15초 하나만

    def test_percentile(self):
        self.assertEqual(sse_lag.percentile([0.1, 0.2, 0.3, 0.4, 2.0], 95), 2.0)
        self.assertEqual(sse_lag.percentile([0.1] * 20 + [5.0], 95), 0.1)


if __name__ == "__main__":
    unittest.main()
