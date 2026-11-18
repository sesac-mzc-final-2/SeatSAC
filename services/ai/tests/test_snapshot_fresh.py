"""질문 시점 갱신(SeatRefresher): 질문마다 오늘 회차를 새로 받고, 못 받아도 질문은 계속한다."""

import asyncio

from seatsac_ai.snapshot.client import SnapshotFetchError
from seatsac_ai.snapshot.fresh import SeatRefresher


class CountingRefresh:
    def __init__(self, delay=0.0, fail=False):
        self.calls = 0
        self.delay = delay
        self.fail = fail

    async def __call__(self):
        self.calls += 1
        await asyncio.sleep(self.delay)
        if self.fail:
            raise SnapshotFetchError("catalog 503")


def test_every_question_refreshes_today():
    asyncio.run(_test_every_question_refreshes_today())


async def _test_every_question_refreshes_today():
    refresh = CountingRefresh()
    refresher = SeatRefresher(refresh, timeout=2)
    assert await refresher.ensure_fresh() is True
    assert await refresher.ensure_fresh() is True
    assert refresh.calls == 2  # 0초 지연: 질문마다 새로 받는다


def test_failed_refresh_keeps_the_question_going():
    asyncio.run(_test_failed_refresh_keeps_the_question_going())


async def _test_failed_refresh_keeps_the_question_going():
    refresher = SeatRefresher(CountingRefresh(fail=True), timeout=2)
    assert await refresher.ensure_fresh() is False  # 예외를 올리지 않는다 → 기존 스냅샷으로 답한다


def test_slow_refresh_gives_up_after_timeout_but_keeps_running():
    asyncio.run(_test_slow_refresh_gives_up_after_timeout_but_keeps_running())


async def _test_slow_refresh_gives_up_after_timeout_but_keeps_running():
    refresh = CountingRefresh(delay=0.2)
    refresher = SeatRefresher(refresh, timeout=0.05)
    assert await refresher.ensure_fresh() is False  # 질문은 0.05초만 기다린다
    task = refresher._running
    assert task is not None and not task.cancelled()
    assert await task is True  # 갱신은 끝까지 돌아 다음 질문이 새 데이터를 쓴다


def test_concurrent_questions_share_one_refresh():
    asyncio.run(_test_concurrent_questions_share_one_refresh())


async def _test_concurrent_questions_share_one_refresh():
    refresh = CountingRefresh(delay=0.05)
    refresher = SeatRefresher(refresh, timeout=2)
    results = await asyncio.gather(*(refresher.ensure_fresh() for _ in range(5)))
    assert results == [True] * 5
    assert refresh.calls == 1  # 동시에 온 5개 질문에 catalog 호출은 한 번


def _validated(sql):
    from seatsac_ai.sql.validator import validate

    return validate(sql)


def test_reads_seats_only_when_sql_touches_remaining_seats():
    from seatsac_ai.snapshot.fresh import reads_seats

    assert reads_seats(_validated("SELECT starts_at, remaining_seats FROM screening"))
    assert reads_seats(_validated("SELECT * FROM screening"))
    assert reads_seats(
        _validated("SELECT s.starts_at FROM screening s WHERE s.remaining_seats > 50")
    )
    assert not reads_seats(_validated("SELECT starts_at FROM screening"))  # 상영 시각만
    assert not reads_seats(_validated("SELECT title FROM movie"))
