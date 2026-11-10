#!/usr/bin/env python3
"""4.2.1 부하 테스트 대상(회차·좌석) 고르기 — 공개 조회 API만 쓴다(토큰·AWS 자격 증명 불필요).

    python3 tests/k6/tools/pick_targets.py --base-url "https://$(cd infra/terraform/envs/staging && terraform output -raw cloudfront_domain_name)/api"
    eval "$(python3 tests/k6/tools/pick_targets.py --base-url … )"     # 환경변수로 바로 받기

시나리오끼리 서로 다른 회차를 고른다 — 한 시나리오가 남긴 선점(TTL 300초)이 다른 시나리오의 측정을 가리지 않게:
  deploy      좌석 ID가 VUS × SEATS_PER_VU개 연속으로 비어 있는 회차 (기본 10 × 20 = 200석) → DEPLOY_SCREENING_ID·DEPLOY_SEAT_ID_FROM
  contention  활성 좌석이 전부 비어 있는 회차의 좌석 하나 → CONTENTION_SCREENING_ID·CONTENTION_SEAT_ID
  user-hold   비어 있는 좌석 2개가 있는 회차 → USERHOLD_SCREENING_ID·USERHOLD_SEAT_IDS
  browse      위 회차들(조회만) + 그 극장·날짜 → BROWSE_SCREENING_IDS·THEATER_ID·THEATER_DATE

예매 마감(상영 시작 5분 전, BKG-02)과 선점 TTL 300초 때문에 **시작까지 --min-lead-minutes(기본 60분) 이상** 남은 회차만 쓴다.
출력(stdout)은 셸 export 줄만, 설명은 stderr로 낸다.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))


class PickError(Exception):
    pass


@dataclass
class Candidate:
    screening_id: int
    theater_id: int
    auditorium_id: int
    starts_at: datetime
    total_seats: int
    label: str
    seats: dict[int, str] = field(default_factory=dict)   # seatId → status (활성 좌석만, BKG-01 스냅샷)

    def available(self) -> list[int]:
        return sorted(s for s, st in self.seats.items() if st == "AVAILABLE")

    def all_free(self) -> bool:
        return bool(self.seats) and all(st == "AVAILABLE" for st in self.seats.values())


class Api:
    def __init__(self, base_url: str, timeout: float = 10.0):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.calls = 0

    def get(self, path: str, **query) -> dict:
        url = f"{self.base}{path}"
        if query:
            url += "?" + urllib.parse.urlencode({k: v for k, v in query.items() if v is not None})
        self.calls += 1
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as res:   # noqa: S310 — 사람이 넘긴 API 주소
                return json.loads(res.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")[:200]
            raise PickError(f"GET {path} → {e.code} {body}") from e
        except urllib.error.URLError as e:
            raise PickError(f"GET {path} 연결 실패: {e.reason} — --base-url 확인(CloudFront면 …/api, port-forward면 http://localhost:8080)") from e


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def longest_run(seat_ids: list[int]) -> tuple[int, int]:
    """정렬된 좌석 ID에서 1씩 이어지는 가장 긴 구간 → (시작, 길이)"""
    best_start, best_len = 0, 0
    start, length, prev = None, 0, None
    for s in seat_ids:
        if prev is not None and s == prev + 1:
            length += 1
        else:
            start, length = s, 1
        if length > best_len:
            best_start, best_len = start, length
        prev = s
    return best_start, best_len


def load_candidates(api: Api, date: str, theater_id: int | None, now: datetime, lead: timedelta) -> list[Candidate]:
    if theater_id is not None:
        theater_ids = [theater_id]
    else:
        theaters = api.get("/theaters", hasLayout="true").get("items", [])
        theater_ids = [t["theaterId"] for t in theaters if t.get("hasLayout")]
        if not theater_ids:
            raise PickError("좌석 배치가 있는 극장(hasLayout)이 없다 — 시드 적재 확인(런북 8-C)")
    out: list[Candidate] = []
    for tid in theater_ids:
        for s in api.get("/screenings", date=date, theaterId=tid).get("items", []):
            starts = parse_time(s["startsAt"])
            if starts < now + lead:
                continue
            out.append(Candidate(
                screening_id=s["screeningId"], theater_id=tid, auditorium_id=s["auditorium"]["auditoriumId"],
                starts_at=starts, total_seats=s.get("totalSeats", 0),
                label=f'{s["theater"]["name"]} {s["auditorium"]["name"]} · {s["movie"]["title"]} · {starts.astimezone(KST):%m/%d %H:%M}',
            ))
    if not out:
        raise PickError(f"{date}에 시작까지 {int(lead.total_seconds() // 60)}분 이상 남은 회차가 없다 — --date로 다른 날을 고른다")
    # 큰 상영관부터(deploy가 연속 좌석을 가장 많이 요구한다), 같으면 늦게 시작하는 회차(테스트 도중 마감에 덜 가깝다)
    out.sort(key=lambda c: (-c.total_seats, -c.starts_at.timestamp()))
    return out


def fill_seats(api: Api, c: Candidate) -> Candidate:
    if not c.seats:
        snap = api.get(f"/screenings/{c.screening_id}/seats")
        c.seats = {s["seatId"]: s["status"] for s in snap.get("seats", [])}
    return c


def pick(api: Api, date: str, theater_id: int | None, now: datetime, lead: timedelta, deploy_seats: int,
         browse_count: int = 5) -> dict:
    cands = load_candidates(api, date, theater_id, now, lead)
    used: set[int] = set()

    def first(pred, why: str) -> Candidate:
        for c in cands:
            if c.screening_id in used:
                continue
            fill_seats(api, c)
            if pred(c):
                used.add(c.screening_id)
                return c
        raise PickError(f"{why} 회차를 찾지 못했다 (후보 {len(cands)}개, 이미 고른 {len(used)}개) — --date·--theater-id를 바꿔 본다")

    deploy = first(lambda c: longest_run(c.available())[1] >= deploy_seats,
                   f"연속 빈 좌석 {deploy_seats}개가 있는")
    contention = first(lambda c: c.all_free(), "활성 좌석이 전부 비어 있는")
    userhold = first(lambda c: len(c.available()) >= 2, "빈 좌석 2개 이상인")

    free = contention.available()
    seat = free[len(free) // 2]               # 가운데쯤 좌석(앞줄 끝이 비활성인 배치가 있다)
    run_start, _ = longest_run(deploy.available())
    browse = [deploy, contention, userhold] + [c for c in cands if c.screening_id not in used]
    browse = browse[:max(3, browse_count)]

    picked = {
        "THEATER_ID": contention.theater_id,
        "THEATER_DATE": date,
        "CONTENTION_SCREENING_ID": contention.screening_id,
        "CONTENTION_SEAT_ID": seat,
        "USERHOLD_SCREENING_ID": userhold.screening_id,
        "USERHOLD_SEAT_IDS": ",".join(str(s) for s in userhold.available()[:2]),
        "DEPLOY_SCREENING_ID": deploy.screening_id,
        "DEPLOY_SEAT_ID_FROM": run_start,
        "BROWSE_SCREENING_IDS": ",".join(str(c.screening_id) for c in browse),
    }
    notes = [
        f"deploy     {deploy.screening_id}  {deploy.label}  (연속 빈 좌석 {longest_run(deploy.available())[1]}개, {run_start}부터)",
        f"contention {contention.screening_id}  {contention.label}  (좌석 {seat}, 활성 {len(contention.seats)}석 전부 빈 상태)",
        f"user-hold  {userhold.screening_id}  {userhold.label}  (좌석 {picked['USERHOLD_SEAT_IDS']})",
        f"browse     {picked['BROWSE_SCREENING_IDS']}  (극장 {picked['THEATER_ID']}, {date})",
        f"가장 이른 시작 {min(c.starts_at for c in (deploy, contention, userhold)).astimezone(KST):%H:%M} KST — 그 5분 전부터는 선점이 400 SCREENING_STARTED(BOOKING_CLOSED)이다",
    ]
    return {"env": picked, "notes": notes}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", required=True, help="공개 API 주소: https://{CloudFront}/api 또는 port-forward http://localhost:8080")
    p.add_argument("--date", help="영업일 YYYY-MM-DD (기본: 오늘 KST)")
    p.add_argument("--theater-id", type=int, help="극장 하나로 좁힌다 (기본: 좌석 배치가 있는 극장 전부)")
    p.add_argument("--min-lead-minutes", type=int, default=60, help="시작까지 최소 남은 시간 (기본 60)")
    p.add_argument("--vus", type=int, default=10, help="deploy VU 수 (기본 10)")
    p.add_argument("--seats-per-vu", type=int, default=20, help="deploy VU당 좌석 수 (기본 20)")
    a = p.parse_args(argv)

    now = datetime.now(timezone.utc)
    date = a.date or now.astimezone(KST).strftime("%Y-%m-%d")
    api = Api(a.base_url)
    try:
        result = pick(api, date, a.theater_id, now, timedelta(minutes=a.min_lead_minutes), a.vus * a.seats_per_vu)
    except PickError as e:
        print(f"❌ {e}", file=sys.stderr)
        return 1
    for line in result["notes"]:
        print(line, file=sys.stderr)
    print(f"(조회 {api.calls}회)", file=sys.stderr)
    for k, v in result["env"].items():
        print(f"export {k}={v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
