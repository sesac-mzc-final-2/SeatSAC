"""질문 시점 잔여석 갱신(SeatRefresher): 필요한 범위만, 동시 질문은 한 번, 확인 불가면 한 번 더."""

import asyncio
from datetime import date, datetime

from seatsac_ai.snapshot.client import SnapshotFetchError
from seatsac_ai.snapshot.fresh import SeatRefresher
from seatsac_ai.snapshot.loader import SeatRefresh
from seatsac_ai.snapshot.mapper import KST

AT = datetime(2026, 11, 18, 12, 0, tzinfo=KST)
TOMORROW = date(2026, 11, 19)


class FakeRefresh:
    def __init__(self, delay=0.0, fail=False, unknown=(0,)):
        self.calls = []
        self.delay = delay
        self.fail = fail
        self.unknown = list(unknown)  # 호출마다 돌려줄 확인 불가 수(모자라면 마지막 값)

    async def __call__(self, days, theater_ids):
        self.calls.append((days, theater_ids))
        await asyncio.sleep(self.delay)
        if self.fail:
            raise SnapshotFetchError("catalog 503")
        unknown = self.unknown[min(len(self.calls), len(self.unknown)) - 1]
        return SeatRefresh(at=AT, rows=10, unknown=unknown)


def run(coro):
    return asyncio.run(coro)


def test_refreshes_only_the_asked_range():
    refresh = FakeRefresh()
    fresh = run(SeatRefresher(refresh, timeout=2).ensure_fresh([TOMORROW], [4]))
    assert fresh.ok and fresh.at == AT and fresh.unknown == 0
    assert refresh.calls == [([TOMORROW], [4])]  # 「내일 건대입구」= 호출 범위 1칸


def test_every_question_gets_its_own_refresh():
    refresh = FakeRefresh()
    refresher = SeatRefresher(refresh, timeout=2)

    async def twice():
        await refresher.ensure_fresh([TOMORROW], [4])
        await refresher.ensure_fresh([TOMORROW], [4])

    run(twice())
    assert len(refresh.calls) == 2  # 0초 지연: 끝난 갱신을 다시 쓰지 않는다


def test_unknown_seats_are_fetched_once_more():
    refresh = FakeRefresh(unknown=(3, 0))
    fresh = run(SeatRefresher(refresh, timeout=2).ensure_fresh([TOMORROW], None))
    assert len(refresh.calls) == 2 and fresh.ok and fresh.unknown == 0


def test_unknown_seats_retry_only_once():
    refresh = FakeRefresh(unknown=(3, 2))
    fresh = run(SeatRefresher(refresh, timeout=2).ensure_fresh([TOMORROW], None))
    assert len(refresh.calls) == 2 and fresh.ok and fresh.unknown == 2  # 답에 「확인 불가」 안내


def test_failed_refresh_keeps_the_question_going():
    fresh = run(SeatRefresher(FakeRefresh(fail=True), timeout=2).ensure_fresh(None, None))
    assert fresh.ok is False  # 예외를 올리지 않는다 → 기존 스냅샷으로 답한다


def test_slow_refresh_gives_up_after_timeout_but_keeps_running():
    async def scenario():
        refresh = FakeRefresh(delay=0.2)
        refresher = SeatRefresher(refresh, timeout=0.05)
        fresh = await refresher.ensure_fresh([TOMORROW], [4])
        task = next(iter(refresher._running.values()))
        assert not task.cancelled()
        assert (await task).ok  # 갱신은 끝까지 돌아 다음 질문이 새 데이터를 쓴다
        return fresh

    assert run(scenario()).ok is False  # 질문은 0.05초만 기다린다


def test_question_waits_no_longer_than_its_remaining_deadline():
    """설정은 2초여도 요청 기한까지 남은 시간(0.05초)만 기다린다."""

    async def scenario():
        refresher = SeatRefresher(FakeRefresh(delay=0.2), timeout=2)
        started = asyncio.get_running_loop().time()
        fresh = await refresher.ensure_fresh([TOMORROW], [4], timeout=0.05)
        waited = asyncio.get_running_loop().time() - started
        await next(iter(refresher._running.values()))
        return fresh, waited

    fresh, waited = run(scenario())
    assert fresh.ok is False and waited < 0.2


def test_concurrent_questions_on_the_same_range_share_one_refresh():
    async def scenario():
        refresh = FakeRefresh(delay=0.05)
        refresher = SeatRefresher(refresh, timeout=2)
        same = [refresher.ensure_fresh([TOMORROW], [4]) for _ in range(5)]
        other = refresher.ensure_fresh([TOMORROW], [16])
        results = await asyncio.gather(*same, other)
        return refresh, results

    refresh, results = run(scenario())
    assert all(r.ok for r in results)
    assert len(refresh.calls) == 2  # 같은 범위 5개 → 1번, 다른 극장 → 1번
