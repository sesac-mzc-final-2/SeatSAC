import asyncio
from datetime import date

import pytest

from seatsac_ai.snapshot.client import SnapshotFetchError
from seatsac_ai.snapshot.loader import SnapshotLoader
from seatsac_ai.snapshot.store import SnapshotStore

from .snapshot_fakes import kst, movie_detail, movie_summary, screening, theater

NOW = kst(2026, 11, 6, 12, 0)


async def refresh_all(loader):
    """테스트용: other → today 순서로 한 번씩."""
    await loader.refresh_other()
    await loader.refresh_today()


class FakeClient:
    def __init__(self):
        self.movies = [movie_summary(1), movie_summary(2, "영화2")]
        self.screenings = {}  # (date iso, theater) -> list
        self.fail_detail = set()
        self.fail_screening = False
        self.theaters = [theater(10)]

    async def list_movies(self):
        return self.movies

    async def get_movie(self, movie_id):
        if movie_id in self.fail_detail:
            raise SnapshotFetchError("x")
        return movie_detail(movie_id, f"영화{movie_id}")

    async def list_theaters(self):
        return self.theaters

    async def list_screenings(self, day, theater_id):
        if self.fail_screening:
            raise SnapshotFetchError("x")
        return self.screenings.get((day.isoformat(), theater_id), [])


def rows(store, sql):
    with store.reader() as conn:
        return conn.execute(sql).fetchall()


def make():
    client, store = FakeClient(), SnapshotStore(clock=lambda: NOW)
    return client, store, SnapshotLoader(client, store, days=3, clock=lambda: NOW)


def test_refresh_all_loads_everything():
    client, store, loader = make()
    client.screenings[("2026-11-06", 10)] = [screening(1)]
    client.screenings[("2026-11-07", 10)] = [
        screening(2, "2026-11-07T10:00:00+09:00", endsAt="2026-11-07T12:00:00+09:00")
    ]
    asyncio.run(refresh_all(loader))
    assert store.is_ready()
    assert rows(store, "SELECT count(*) FROM movie") == [(2,)]
    assert rows(store, "SELECT screening_id FROM screening ORDER BY 1") == [(1,), (2,)]


def test_empty_screenings_is_success():
    _, store, loader = make()
    asyncio.run(refresh_all(loader))
    assert store.is_ready()
    assert rows(store, "SELECT count(*) FROM screening") == [(0,)]


def test_detail_failure_falls_back_to_summary():
    client, store, loader = make()
    client.fail_detail = {2}
    asyncio.run(refresh_all(loader))
    assert rows(store, "SELECT synopsis FROM movie ORDER BY movie_id") == [("줄거리",), (None,)]


def test_screening_failure_keeps_previous_snapshot():
    client, store, loader = make()
    client.screenings[("2026-11-06", 10)] = [screening(1)]
    asyncio.run(refresh_all(loader))
    client.fail_screening = True
    with pytest.raises(SnapshotFetchError):
        asyncio.run(loader.refresh_today())
    assert rows(store, "SELECT screening_id FROM screening") == [(1,)]


def test_today_refresh_replaces_today_only():
    client, store, loader = make()
    client.screenings[("2026-11-07", 10)] = [
        screening(2, "2026-11-07T10:00:00+09:00", endsAt="2026-11-07T12:00:00+09:00")
    ]
    client.screenings[("2026-11-06", 10)] = [screening(1)]
    asyncio.run(refresh_all(loader))
    client.screenings[("2026-11-06", 10)] = [screening(3)]
    asyncio.run(loader.refresh_today())
    assert rows(store, "SELECT screening_id FROM screening ORDER BY 1") == [(2,), (3,)]


def test_bad_rows_skipped_but_all_bad_fails():
    client, store, loader = make()
    bad = screening(9)
    del bad["movie"]
    client.screenings[("2026-11-06", 10)] = [screening(1), bad]
    asyncio.run(refresh_all(loader))
    assert rows(store, "SELECT screening_id FROM screening") == [(1,)]
    client.screenings[("2026-11-06", 10)] = [bad]
    with pytest.raises(ValueError):
        asyncio.run(loader.refresh_today())


def test_wrong_type_row_is_skipped_not_whole_refresh():
    client, store, loader = make()
    client.screenings[("2026-11-06", 10)] = [screening(1), screening("abc")]
    asyncio.run(refresh_all(loader))
    assert rows(store, "SELECT screening_id FROM screening") == [(1,)]


def test_today_first_then_other():
    # 운영에서는 두 작업이 동시에 시작해 today가 먼저 끝날 수 있다
    client, store, loader = make()
    client.screenings[("2026-11-06", 10)] = [screening(1)]
    client.screenings[("2026-11-07", 10)] = [
        screening(2, "2026-11-07T10:00:00+09:00", endsAt="2026-11-07T12:00:00+09:00")
    ]
    asyncio.run(loader.refresh_today())
    assert not store.is_ready()
    asyncio.run(loader.refresh_other())
    assert store.is_ready()
    assert rows(store, "SELECT screening_id FROM screening ORDER BY 1") == [(1,), (2,)]


def test_zero_movies_fails_and_keeps_previous():
    client, store, loader = make()
    asyncio.run(refresh_all(loader))
    client.movies = []
    with pytest.raises(SnapshotFetchError):
        asyncio.run(loader.refresh_other())
    assert rows(store, "SELECT count(*) FROM movie") == [(2,)]


def test_zero_theaters_on_first_load_is_not_ready():
    client, store, loader = make()
    client.theaters = []
    with pytest.raises(SnapshotFetchError):
        asyncio.run(loader.refresh_other())
    with pytest.raises(SnapshotFetchError):
        asyncio.run(loader.refresh_today())
    assert not store.is_ready()


def test_detail_failure_keeps_previous_row():
    client, store, loader = make()
    asyncio.run(refresh_all(loader))
    client.fail_detail = {1}
    asyncio.run(loader.refresh_other())
    assert rows(store, "SELECT synopsis FROM movie WHERE movie_id = 1") == [("줄거리",)]


def test_movie_dropped_from_list_is_removed():
    client, store, loader = make()
    asyncio.run(refresh_all(loader))
    client.movies = [movie_summary(1)]
    asyncio.run(loader.refresh_other())
    assert rows(store, "SELECT movie_id FROM movie") == [(1,)]


def test_seat_refresh_replaces_only_the_asked_day_and_theater():
    """질문 시점 갱신: 「내일 극장10」만 바꾸고 오늘 회차·다른 극장·주기 갱신 시각은 그대로."""
    clock = {"now": NOW}
    client = FakeClient()
    client.theaters = [theater(10), theater(20)]
    store = SnapshotStore(clock=lambda: clock["now"])
    loader = SnapshotLoader(client, store, days=3, clock=lambda: clock["now"])
    tomorrow, end = "2026-11-07T10:00:00+09:00", "2026-11-07T12:00:00+09:00"
    client.screenings[("2026-11-06", 10)] = [screening(1)]
    client.screenings[("2026-11-07", 10)] = [screening(2, tomorrow, endsAt=end, remainingSeats=100)]
    client.screenings[("2026-11-07", 20)] = [
        screening(
            3, tomorrow, endsAt=end, remainingSeats=50, theater={"theaterId": 20, "name": "극장20"}
        )
    ]
    asyncio.run(refresh_all(loader))
    as_of = store.as_of()

    clock["now"] = kst(2026, 11, 6, 12, 5)
    client.screenings[("2026-11-07", 10)] = [screening(2, tomorrow, endsAt=end, remainingSeats=98)]
    client.screenings[("2026-11-07", 20)] = [
        screening(
            3, tomorrow, endsAt=end, remainingSeats=1, theater={"theaterId": 20, "name": "극장20"}
        )
    ]
    result = asyncio.run(loader.refresh_screenings([date(2026, 11, 7)], [10]))
    assert rows(store, "SELECT screening_id, remaining_seats FROM screening ORDER BY 1") == [
        (1, 40),
        (2, 98),  # 새로 받음
        (3, 50),  # 묻지 않은 극장은 그대로
    ]
    assert result.rows == 1 and result.unknown == 0 and result.at == clock["now"]
    assert store.as_of() == as_of  # 주기 갱신 시각은 건드리지 않는다(답의 asOf는 따로)


def test_total_seats_are_stored_as_unknown():
    """booking이 늦게 답해 catalog가 전체 좌석 수(TOTAL)로 채운 값은 잔여석이 아니다 → NULL."""
    client, store, loader = make()
    client.screenings[("2026-11-06", 10)] = [
        screening(1, remainingSeats=100, remainingSeatsSource="TOTAL")
    ]
    asyncio.run(refresh_all(loader))
    result = asyncio.run(loader.refresh_screenings([date(2026, 11, 6)], None))
    assert rows(store, "SELECT remaining_seats, remaining_seats_source FROM screening") == [
        (None, "TOTAL")
    ]
    assert result.unknown == 1


def test_seat_refresh_ignores_days_outside_the_snapshot():
    client, store, loader = make()
    asyncio.run(refresh_all(loader))
    result = asyncio.run(loader.refresh_screenings([date(2026, 12, 25)], None))
    assert result.rows == 0  # 7일 밖은 받지 않는다(어차피 스냅샷에 없다)
