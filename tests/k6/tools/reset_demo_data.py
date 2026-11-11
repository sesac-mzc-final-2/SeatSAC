#!/usr/bin/env python3
"""5.1.1·5.1.2 데모·리허설 뒤 데이터 정리 — 공개 API만 쓴다(RDS·Valkey 직접 수정 없음).

    python3 tests/k6/tools/reset_demo_data.py --base-url "$API" --tokens-file ~/.seatsac/k6-tokens.txt \
        --tokens-file ~/.seatsac/demo-tokens.txt --screening-id $DEMO_SCREENING          # 미리보기(바꾸지 않음)
    … 같은 명령 + --apply                                                                   # 실제로 취소

하는 일
  1. 토큰마다 `GET /users/me/bookings?status=CONFIRMED`(전체 쪽)를 읽어 **아직 시작하지 않은 회차**의 확정 예매를 고른다
  2. `--apply`면 `POST /bookings/{id}/cancel`로 취소한다(환불까지 — BKG-07). 시작한 회차는 취소할 수 없어(400) 건너뛴다
  3. `--screening-id` 회차의 좌석 상태 수를 센다(`GET /screenings/{id}/seats`) — 전부 AVAILABLE이면 깨끗하다

하지 않는 일: 선점(PENDING)은 사용자 API로 목록을 볼 수 없다 → 300초 뒤 저절로 풀린다(런북 5.1.1 데이터 초기화 2단계).
토큰은 화면·로그에 쓰지 않고 「토큰 #번호」로만 가리킨다.
종료 코드: 0 정리할 것 없음 또는 모두 취소 · 1 일부 실패(만료 토큰 포함) · 2 사용법·연결 실패
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

# 시작 직전 회차는 취소 요청이 가는 사이에 시작해 400이 될 수 있다 → 여유를 두고 건너뛴다
START_MARGIN = timedelta(minutes=1)
PAGE_SIZE = 50   # 계약 최대


class ApiError(Exception):
    def __init__(self, status: int, code: str):
        super().__init__(f"{status} {code}")
        self.status = status
        self.code = code


@dataclass
class Booking:
    token_no: int
    booking_id: int
    screening_id: int | None
    starts_at: datetime | None
    seats: str


class Api:
    def __init__(self, base_url: str, timeout: float = 10.0):
        self.base = base_url.rstrip("/")
        self.timeout = timeout

    def request(self, method: str, path: str, token: str | None = None, **query) -> dict:
        url = f"{self.base}{path}"
        if query:
            url += "?" + urllib.parse.urlencode(query)
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if method == "POST":
            headers["Idempotency-Key"] = str(uuid.uuid4())
        req = urllib.request.Request(url, method=method, headers=headers, data=b"" if method == "POST" else None)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as res:   # noqa: S310 — 사람이 넘긴 API 주소
                raw = res.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            with e:   # 오류 응답도 연결을 닫는다
                try:
                    code = json.loads(e.read() or b"{}").get("code", "")
                except ValueError:
                    code = ""
            raise ApiError(e.code, code) from e


def read_tokens(paths: list[Path]) -> list[str]:
    tokens: list[str] = []
    for p in paths:
        for line in p.read_text(encoding="utf-8").splitlines():
            t = line.strip()
            if t and t not in tokens:
                tokens.append(t)
    return tokens


def confirmed_bookings(api: Api, token: str, token_no: int) -> list[Booking]:
    out: list[Booking] = []
    page = 0
    while True:
        body = api.request("GET", "/users/me/bookings", token, status="CONFIRMED", page=page, size=PAGE_SIZE)
        for b in body.get("content", []):
            s = b.get("screening")
            out.append(Booking(
                token_no=token_no,
                booking_id=b["bookingId"],
                screening_id=s["screeningId"] if s else None,
                starts_at=datetime.fromisoformat(s["startsAt"]) if s else None,
                seats=",".join(b.get("seatLabels", [])),
            ))
        page += 1
        if page >= body.get("totalPages", 0):
            return out


def seat_summary(api: Api, screening_id: int) -> Counter:
    snap = api.request("GET", f"/screenings/{screening_id}/seats")
    return Counter(s["status"] for s in snap.get("seats", []))


def run(api: Api, tokens: list[str], screening_ids: list[int], apply: bool, now: datetime, out=sys.stdout) -> int:
    failed = 0
    targets: list[tuple[Booking, str]] = []
    for no, token in enumerate(tokens, start=1):
        try:
            found = confirmed_bookings(api, token, no)
        except ApiError as e:
            failed += 1
            hint = " — 토큰 만료: loadtest_users.py refresh 뒤 tokens --out" if e.status == 401 else ""
            print(f"❌ 토큰 #{no}: 예매 목록 실패 {e.status} {e.code}{hint}", file=out)
            continue
        for b in found:
            if b.starts_at is None:
                print(f"⚠️  토큰 #{no} 예매 {b.booking_id}: 회차 정보 없음(catalog 실패) — 건너뜀", file=out)
            elif b.starts_at <= now + START_MARGIN:
                pass   # 이미 시작했거나 곧 시작 — 취소 불가(400), 지난 회차는 데모에 쓰지 않으므로 그대로 둔다
            else:
                targets.append((b, token))

    verb = "취소" if apply else "취소할 예정(미리보기)"
    for b, token in targets:
        line = f"토큰 #{b.token_no} 예매 {b.booking_id} 회차 {b.screening_id} {b.starts_at:%m/%d %H:%M} 좌석 {b.seats}"
        if not apply:
            print(f"· {verb}: {line}", file=out)
            continue
        try:
            api.request("POST", f"/bookings/{b.booking_id}/cancel", token)
            print(f"✅ {verb}: {line}", file=out)
        except ApiError as e:
            if e.status == 409:   # 이미 다른 경로로 취소됨 — 목표 상태라 실패로 세지 않는다
                print(f"· 이미 처리됨({e.code}): {line}", file=out)
            else:
                failed += 1
                print(f"❌ 취소 실패 {e.status} {e.code}: {line}", file=out)
    print(f"확정 예매(시작 전) {len(targets)}건 {verb}" + ("" if apply else " — 실제로 취소하려면 --apply"), file=out)

    for sid in screening_ids:
        try:
            c = seat_summary(api, sid)
        except ApiError as e:
            failed += 1
            print(f"❌ 회차 {sid} 좌석 조회 실패 {e.status} {e.code}", file=out)
            continue
        taken = {k: v for k, v in c.items() if k != "AVAILABLE"}
        state = "깨끗함" if not taken else "남은 좌석 " + " · ".join(f"{k} {v}" for k, v in sorted(taken.items()))
        print(f"회차 {sid}: AVAILABLE {c.get('AVAILABLE', 0)} · {state}", file=out)
    return 1 if failed else 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", required=True, help="공개 API: https://{CloudFront}/api")
    p.add_argument("--tokens-file", type=Path, action="append", default=[], help="줄당 토큰 1개(여러 번 줄 수 있다)")
    p.add_argument("--screening-id", type=int, action="append", default=[], help="좌석 상태를 셀 회차(여러 번)")
    p.add_argument("--apply", action="store_true", help="실제로 취소한다(없으면 미리보기)")
    a = p.parse_args(argv)
    try:
        tokens = read_tokens(a.tokens_file)
    except OSError as e:
        print(f"❌ 토큰 파일을 읽지 못했다: {e}", file=sys.stderr)
        return 2
    if not tokens and not a.screening_id:
        print("❌ --tokens-file 또는 --screening-id가 필요하다", file=sys.stderr)
        return 2
    try:
        return run(Api(a.base_url), tokens, a.screening_id, a.apply, datetime.now(timezone.utc))
    except urllib.error.URLError as e:
        print(f"❌ 연결 실패: {e.reason} — --base-url 확인(…/api)", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
