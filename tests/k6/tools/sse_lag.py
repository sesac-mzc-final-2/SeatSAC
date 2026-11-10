#!/usr/bin/env python3
"""4.2.1 「다른 화면 반영 1초 이내」 측정 — 좌석 SSE(BKG-05)를 열어 두고 이벤트마다 지연을 잰다.

    python3 tests/k6/tools/sse_lag.py --url "https://{CloudFront}/api/screenings/{screeningId}/seats/stream" --duration 120

k6에는 SSE 클라이언트가 없어 따로 잰다. 화면과 같은 경로(CloudFront → ALB → gateway → booking)로 붙는다.
  지연 = 받은 시각 − 이벤트 occurredAt(booking이 커밋 뒤 발행한 시각, seat-event.v1)
  하트비트 간격 = `: ping` 줄 사이 시간 (15초여야 한다. 몰려서 오면 버퍼링 — 런타임 계약 6장 SSE ①~④)

이 PC 시계와 서버 시계 차이가 그대로 지연에 더해진다 → 시계 동기화(macOS 기본 켜짐) 상태에서 잰다.
음수 지연이 보이면 이 PC 시계가 늦은 것이다.
종료 코드: 0 이벤트 p95 ≤ --max-lag(기본 1.0초) · 1 초과 또는 이벤트 0건 · 2 연결 실패
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Iterable, Iterator


def percentile(values: list[float], p: float) -> float:
    if not values:
        return math.nan
    s = sorted(values)
    k = max(0, math.ceil(p / 100 * len(s)) - 1)
    return s[k]


def parse_stream(lines: Iterable[str], clock=time.time) -> Iterator[tuple[str, object, float]]:
    """SSE 줄 → ('event', dict, 받은 시각) 또는 ('ping', None, 받은 시각). 빈 줄에서 이벤트를 끝낸다"""
    data: list[str] = []
    for raw in lines:
        line = raw.rstrip("\r\n")
        now = clock()
        if line == "":
            if data:
                try:
                    yield "event", json.loads("\n".join(data)), now
                except json.JSONDecodeError:
                    pass
                data = []
            continue
        if line.startswith(":"):
            # 하트비트는 `: ping`만 센다. 연결 직후 `: connected`(booking SeatStreamService)는 간격 계산에서 뺀다
            if line[1:].strip() == "ping":
                yield "ping", None, now
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())


def lag_seconds(event: dict, received: float) -> float | None:
    occurred = event.get("occurredAt")
    if not occurred:
        return None
    return received - datetime.fromisoformat(occurred.replace("Z", "+00:00")).timestamp()


def measure(lines: Iterable[str], duration: float, clock=time.time, out=sys.stdout) -> dict:
    started = clock()
    lags: list[float] = []
    pings: list[float] = []
    for kind, event, received in parse_stream(lines, clock):
        if kind == "ping":
            pings.append(received)
        else:
            lag = lag_seconds(event, received)
            if lag is not None:
                lags.append(lag)
                print(f"{datetime.fromtimestamp(received, timezone.utc).astimezone():%H:%M:%S.%f}"[:-3]
                      + f"  seq={event.get('seq')} {event.get('type')} 좌석 {len(event.get('seatIds', []))}개  지연 {lag * 1000:.0f}ms",
                      file=out, flush=True)
        if clock() - started >= duration:
            break
    gaps = [b - a for a, b in zip(pings, pings[1:])]
    return {
        "events": len(lags),
        "p50": percentile(lags, 50), "p95": percentile(lags, 95), "max": max(lags) if lags else math.nan,
        "pings": len(pings), "ping_gap_max": max(gaps) if gaps else math.nan,
        "ping_gap_min": min(gaps) if gaps else math.nan,
    }


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", required=True, help="https://{CloudFront}/api/screenings/{id}/seats/stream")
    p.add_argument("--duration", type=float, default=120, help="측정 시간(초, 기본 120)")
    p.add_argument("--max-lag", type=float, default=1.0, help="이벤트 지연 p95 합격선(초, 기본 1.0)")
    a = p.parse_args(argv)

    req = urllib.request.Request(a.url, headers={"Accept": "text/event-stream", "Cache-Control": "no-cache"})
    try:
        res = urllib.request.urlopen(req, timeout=a.duration + 30)   # noqa: S310 — 사람이 넘긴 주소
    except (urllib.error.URLError, OSError) as e:
        print(f"❌ 연결 실패: {e}", file=sys.stderr)
        return 2
    ctype = res.headers.get("Content-Type", "")
    if "text/event-stream" not in ctype:
        print(f"❌ Content-Type이 text/event-stream이 아니다: {ctype}", file=sys.stderr)
        return 2
    print(f"연결됨 — {a.duration:.0f}초 동안 이벤트를 기다린다 (다른 창에서 같은 회차를 선점해 본다)", file=sys.stderr)
    try:
        r = measure((b.decode("utf-8", "replace") for b in res), a.duration)
    except KeyboardInterrupt:
        print("중단", file=sys.stderr)
        return 1
    finally:
        res.close()

    def ms(v: float) -> str:
        return "-" if math.isnan(v) else f"{v * 1000:.0f}ms"

    def sec(v: float) -> str:
        return "-" if math.isnan(v) else f"{v:.1f}s"

    print(f"\n이벤트 {r['events']}건 · 지연 p50 {ms(r['p50'])} · p95 {ms(r['p95'])} · 최대 {ms(r['max'])}   ← p95 {a.max_lag:.1f}초 이하")
    print(f"하트비트 {r['pings']}회 · 간격 최소 {sec(r['ping_gap_min'])} · 최대 {sec(r['ping_gap_max'])}   ← 15초 안팎이어야 한다(몰려 오면 버퍼링)")
    if r["events"] == 0:
        print("❌ 이벤트 0건 — 측정 중에 같은 회차를 선점했는지 확인", file=sys.stderr)
        return 1
    return 0 if r["p95"] <= a.max_lag else 1


if __name__ == "__main__":
    sys.exit(main())
