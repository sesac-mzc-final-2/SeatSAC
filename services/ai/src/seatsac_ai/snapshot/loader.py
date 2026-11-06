"""적재 지휘 — 무엇을 어떤 순서로 가져와 스냅샷에 넣을지.

두 가지 속도로 나눈다 (docs/service-runtime-contract.md 9-1).
  - other(10분): 영화 · 극장 · 내일 이후 회차(오늘+1 ~ 오늘+days-1)
  - today(1분):  오늘 회차 — 잔여석이 계속 변한다

원칙
  - **먼저 전부 가져온 뒤 한 번에 넣는다.** 호출이 중간에 실패하면 아무것도 바꾸지 않는다
    (이전 스냅샷 유지).
  - 영화 상세 호출 하나가 실패하면 그 영화만 목록(요약) 정보로 대체한다(줄거리 등은 비게 됨).
  - 응답 한 줄이 계약과 다르면 그 줄만 건너뛰고 경고를 남긴다. 줄이 있는데 하나도 못 쓰면 갱신 실패.
  - 응답이 **비어 있는 것은 실패가 아니다**(편성이 없는 날). 실패는 호출 오류다.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable, Iterable
from datetime import date, datetime, timedelta
from typing import Any

from seatsac_ai.snapshot import schema
from seatsac_ai.snapshot.client import PublicApiClient
from seatsac_ai.snapshot.mapper import (
    KST,
    MappingError,
    format_minute,
    movie_row,
    screening_row,
    theater_row,
)
from seatsac_ai.snapshot.store import SnapshotStore

log = logging.getLogger(__name__)

_MAX_CONCURRENCY = 8  # 동시에 보내는 호출 수. 10분마다 약 50회가 한꺼번에 몰리지 않게 한다


async def _gather_limited(calls: Iterable[Callable[[], Awaitable[Any]]]) -> list[Any]:
    """호출을 동시 `_MAX_CONCURRENCY`개까지 실행. 예외는 결과 자리에 담아 돌려준다."""
    semaphore = asyncio.Semaphore(_MAX_CONCURRENCY)

    async def run(call: Callable[[], Awaitable[Any]]) -> Any:
        async with semaphore:
            try:
                return await call()
            except Exception as exc:  # 호출 쪽이 하나씩 판단한다
                return exc

    return await asyncio.gather(*(run(c) for c in calls))


def _first_error(results: Iterable[Any]) -> Exception | None:
    return next((r for r in results if isinstance(r, Exception)), None)


def _map_rows(items: Iterable[Any], what: str, mapper: Callable[[Any], dict[str, Any]]) -> list:
    rows: list[dict[str, Any]] = []
    skipped = 0
    total = 0
    for item in items:
        total += 1
        try:
            rows.append(mapper(item))
        # MappingError 포함. int("abc") 같은 형 오류도 그 줄만 건너뛴다
        except (ValueError, TypeError) as exc:
            skipped += 1
            log.warning("snapshot %s 한 줄 건너뜀: %s", what, exc)
    if total and not rows:
        raise MappingError(f"{what}: {total}줄 모두 계약과 다름")
    if skipped:
        log.warning("snapshot %s: %d/%d줄 건너뜀", what, skipped, total)
    return rows


class SnapshotLoader:
    def __init__(
        self,
        client: PublicApiClient,
        store: SnapshotStore,
        days: int = 7,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._client = client
        self._store = store
        self._days = max(1, days)
        self._clock = clock or (lambda: datetime.now(KST))
        self._theater_ids: list[int] = []

    async def refresh_all(self) -> None:
        await self.refresh_other()
        await self.refresh_today()

    # --- 10분: 영화·극장·내일 이후 회차 ------------------------------------------
    async def refresh_other(self) -> None:
        now = self._clock()
        today = now.date()
        fetched_at = format_minute(now)

        summaries = await self._client.list_movies()
        details = await _gather_limited(
            [lambda m=m: self._client.get_movie(int(m["movieId"])) for m in summaries]
        )
        movie_sources: list[Any] = []
        for summary, detail in zip(summaries, details, strict=True):
            if isinstance(detail, Exception):
                log.warning(
                    "movie %s 상세 실패 → 목록 정보로 대체: %s", summary.get("movieId"), detail
                )
                movie_sources.append(summary)
            else:
                movie_sources.append(detail)
        movies = _map_rows(movie_sources, "movie", movie_row)

        theaters_raw = await self._client.list_theaters()
        theaters = _map_rows(theaters_raw, "theater", theater_row)
        self._theater_ids = [t["theater_id"] for t in theaters]

        later = [today + timedelta(days=i) for i in range(1, self._days)]
        screenings = await self._fetch_screenings(later, fetched_at)

        today_text = today.isoformat()

        def mutate(conn):
            conn.execute("DELETE FROM movie")
            conn.executemany(schema.MOVIE_INSERT, movies)
            conn.execute("DELETE FROM theater")
            conn.executemany(schema.THEATER_INSERT, theaters)
            # 오늘 이외(지난 날짜 포함)를 지우고 새로 넣는다. 오늘 회차는 today 범위의 몫이다.
            conn.execute("DELETE FROM screening WHERE business_date <> ?", (today_text,))
            conn.executemany(schema.SCREENING_INSERT, screenings)

        self._store.update("other", ("movie", "theater", "screening"), mutate)
        log.info(
            "snapshot other 갱신: 영화 %d · 극장 %d · 회차 %d",
            len(movies),
            len(theaters),
            len(screenings),
        )

    # --- 1분: 오늘 회차 -----------------------------------------------------------
    async def refresh_today(self) -> None:
        now = self._clock()
        today = now.date()
        fetched_at = format_minute(now)

        if not self._theater_ids:
            theaters = _map_rows(await self._client.list_theaters(), "theater", theater_row)
            self._theater_ids = [t["theater_id"] for t in theaters]
        screenings = await self._fetch_screenings([today], fetched_at)
        today_text = today.isoformat()

        def mutate(conn):
            # 오늘 + 지난 날짜(자정이 지나 어제가 된 행)를 지우고 오늘 것을 새로 넣는다.
            conn.execute(
                "DELETE FROM screening WHERE business_date = ? OR business_date < ?",
                (today_text, today_text),
            )
            conn.executemany(schema.SCREENING_INSERT, screenings)

        self._store.update("today", ("screening",), mutate)
        log.info("snapshot today 갱신: 오늘 회차 %d", len(screenings))

    # --- 공통 -----------------------------------------------------------------
    async def _fetch_screenings(self, days: list[date], fetched_at: str) -> list[dict[str, Any]]:
        pairs = [(day, theater_id) for day in days for theater_id in self._theater_ids]
        results = await _gather_limited(
            [lambda d=d, t=t: self._client.list_screenings(d, t) for d, t in pairs]
        )
        error = _first_error(results)
        if error is not None:
            # 일부 극장·날짜만 실패해도 반쯤 새로운 스냅샷을 만들지 않는다.
            raise error
        items = [item for result in results for item in result]
        return _map_rows(items, "screening", lambda item: screening_row(item, fetched_at))
