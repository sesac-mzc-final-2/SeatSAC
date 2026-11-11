"""reset_demo_data 단위 테스트 — 가짜 공개 API 서버로 돈다(네트워크·토큰 없음).

    python3 -m unittest discover -s tests/k6/tools -v
"""
from __future__ import annotations

import io
import json
import sys
import tempfile
import threading
import unittest
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))

import reset_demo_data as rd  # noqa: E402

NOW = datetime(2026, 11, 16, 5, 0, tzinfo=timezone.utc)   # 14:00 KST


def booking(bid: int, starts_kst: str | None, sid: int = 7) -> dict:
    screening = None if starts_kst is None else {
        "screeningId": sid, "movieTitle": "영화", "theaterName": "건대입구", "auditoriumName": "6관",
        "startsAt": starts_kst, "endsAt": starts_kst,
    }
    return {"bookingId": bid, "status": "CONFIRMED", "seatLabels": ["A1"], "amount": 15000,
            "createdAt": "2026-11-16T13:00:00+09:00", "confirmedAt": None, "canceledAt": None, "screening": screening}


class FakeApi(BaseHTTPRequestHandler):
    pages: dict = {}        # token → [page0 content, page1 content, …]
    cancel_status: dict = {}
    posts: list = []
    seats: dict = {}

    def _send(self, status: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.end_headers()
        self.wfile.write(data)

    def do_GET(self):  # noqa: N802
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/users/me/bookings":
            token = self.headers.get("Authorization", "").removeprefix("Bearer ")
            if token not in FakeApi.pages:
                return self._send(401, {"code": "UNAUTHORIZED"})
            assert q["status"] == "CONFIRMED"
            pages = FakeApi.pages[token]
            page = int(q["page"])
            return self._send(200, {"content": pages[page] if page < len(pages) else [], "page": page,
                                    "size": 50, "totalElements": sum(map(len, pages)), "totalPages": len(pages)})
        if u.path.startswith("/screenings/") and u.path.endswith("/seats"):
            sid = int(u.path.split("/")[2])
            return self._send(200, {"seats": FakeApi.seats[sid]})
        self._send(404, {"code": "NOT_FOUND"})

    def do_POST(self):  # noqa: N802
        bid = int(self.path.split("/")[2])
        assert self.headers.get("Idempotency-Key")
        FakeApi.posts.append(bid)
        status = FakeApi.cancel_status.get(bid, 200)
        self._send(status, {"code": "BOOKING_STATE_CONFLICT"} if status == 409 else {"status": "CANCELED"})

    def log_message(self, *args):
        pass


class ResetDemoDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), FakeApi)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.api = rd.Api(f"http://127.0.0.1:{cls.server.server_port}")

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        FakeApi.posts = []
        FakeApi.cancel_status = {}
        FakeApi.pages = {
            "tok-a": [[booking(1, "2026-11-16T15:00:00+09:00"), booking(2, "2026-11-16T10:00:00+09:00")],
                      [booking(3, "2026-11-16T14:00:30+09:00"), booking(4, None)]],
            "tok-b": [[booking(5, "2026-11-17T09:00:00+09:00", sid=8)]],
        }
        FakeApi.seats = {7: [{"seatId": 1, "status": "AVAILABLE"}, {"seatId": 2, "status": "RESERVED"},
                             {"seatId": 3, "status": "HELD"}],
                         8: [{"seatId": 9, "status": "AVAILABLE"}]}

    def run_tool(self, tokens, apply, screenings=()):
        out = io.StringIO()
        rc = rd.run(self.api, tokens, list(screenings), apply, NOW, out)
        return rc, out.getvalue()

    def test_미리보기는_취소하지_않고_시작_전_확정_예매만_고른다(self):
        rc, out = self.run_tool(["tok-a", "tok-b"], apply=False)
        self.assertEqual(rc, 0)
        self.assertEqual(FakeApi.posts, [])
        self.assertIn("예매 1 ", out)
        self.assertIn("예매 5 ", out)
        self.assertNotIn("예매 2 ", out)   # 이미 시작
        self.assertNotIn("예매 3 ", out)   # 1분 안에 시작 — 여유 밖
        self.assertIn("예매 4: 회차 정보 없음", out)
        self.assertIn("2건 취소할 예정", out)
        self.assertIn("건너뜀 2건", out)   # 예매 2(시작함)·3(1분 안)

    def test_apply는_두_쪽_모두_읽고_시작_전_예매만_취소한다(self):
        rc, _ = self.run_tool(["tok-a", "tok-b"], apply=True)
        self.assertEqual(rc, 0)
        self.assertEqual(sorted(FakeApi.posts), [1, 5])

    def test_이미_취소된_예매는_실패로_세지_않는다(self):
        FakeApi.cancel_status = {1: 409}
        rc, out = self.run_tool(["tok-a"], apply=True)
        self.assertEqual(rc, 0)
        self.assertIn("이미 처리됨(BOOKING_STATE_CONFLICT)", out)

    def test_만료_토큰과_취소_실패는_종료_코드_1이고_토큰_값을_출력하지_않는다(self):
        FakeApi.cancel_status = {5: 503}
        rc, out = self.run_tool(["tok-expired", "tok-b"], apply=True)
        self.assertEqual(rc, 1)
        self.assertIn("토큰 #1: 예매 목록 실패 401 UNAUTHORIZED — 토큰 만료", out)
        self.assertIn("취소 실패 503", out)
        self.assertNotIn("tok-", out)

    def test_회차_좌석_상태를_센다(self):
        _, out = self.run_tool([], apply=False, screenings=[7, 8])
        self.assertIn("회차 7: AVAILABLE 1 · 남은 좌석 HELD 1 · RESERVED 1", out)
        self.assertIn("회차 8: AVAILABLE 1 · 깨끗함", out)

    def test_토큰_파일은_빈_줄과_중복을_뺀다(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "tokens.txt"
            p.write_text("a\n\n b \na\n", encoding="utf-8")
            self.assertEqual(rd.read_tokens([p]), ["a", "b"])


if __name__ == "__main__":
    unittest.main()
