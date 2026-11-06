import asyncio

import pytest

from seatsac_ai.snapshot.client import SnapshotFetchError
from seatsac_ai.snapshot.loader import SnapshotLoader
from seatsac_ai.snapshot.store import SnapshotStore

from .snapshot_fakes import kst, movie_detail, movie_summary, screening, theater

NOW = kst(2026, 11, 6, 12, 0)


class FakeClient:
    def __init__(self):
        self.screenings = {}  # (date iso, theater) -> list
        self.fail_detail = set()
        self.fail_screening = False
        self.theaters = [theater(10)]

    async def list_movies(self):
        return [movie_summary(1), movie_summary(2, "영화2")]

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
    asyncio.run(loader.refresh_all())
    assert store.is_ready()
    assert rows(store, "SELECT count(*) FROM movie") == [(2,)]
    assert rows(store, "SELECT screening_id FROM screening ORDER BY 1") == [(1,), (2,)]


def test_empty_screenings_is_success():
    _, store, loader = make()
    asyncio.run(loader.refresh_all())
    assert store.is_ready()
    assert rows(store, "SELECT count(*) FROM screening") == [(0,)]


def test_detail_failure_falls_back_to_summary():
    client, store, loader = make()
    client.fail_detail = {2}
    asyncio.run(loader.refresh_all())
    assert rows(store, "SELECT synopsis FROM movie ORDER BY movie_id") == [("줄거리",), (None,)]


def test_screening_failure_keeps_previous_snapshot():
    client, store, loader = make()
    client.screenings[("2026-11-06", 10)] = [screening(1)]
    asyncio.run(loader.refresh_all())
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
    asyncio.run(loader.refresh_all())
    client.screenings[("2026-11-06", 10)] = [screening(3)]
    asyncio.run(loader.refresh_today())
    assert rows(store, "SELECT screening_id FROM screening ORDER BY 1") == [(2,), (3,)]


def test_bad_rows_skipped_but_all_bad_fails():
    client, store, loader = make()
    bad = screening(9)
    del bad["movie"]
    client.screenings[("2026-11-06", 10)] = [screening(1), bad]
    asyncio.run(loader.refresh_all())
    assert rows(store, "SELECT screening_id FROM screening") == [(1,)]
    client.screenings[("2026-11-06", 10)] = [bad]
    with pytest.raises(ValueError):
        asyncio.run(loader.refresh_today())


def test_wrong_type_row_is_skipped_not_whole_refresh():
    client, store, loader = make()
    client.screenings[("2026-11-06", 10)] = [screening(1), screening("abc")]
    asyncio.run(loader.refresh_all())
    assert rows(store, "SELECT screening_id FROM screening") == [(1,)]
