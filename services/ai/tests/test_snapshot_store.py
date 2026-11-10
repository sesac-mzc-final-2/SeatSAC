import sqlite3
import threading

import pytest

from seatsac_ai.snapshot import schema
from seatsac_ai.snapshot.store import SnapshotNotReady, SnapshotStore

from .snapshot_fakes import kst


def _movie(conn, movie_id=1):
    conn.execute(
        schema.MOVIE_INSERT,
        {
            c: (movie_id if c == "movie_id" else "t" if c == "title" else None)
            for c in schema.MOVIE_COLUMNS
        },
    )


def _ready(store):
    store.update("other", ("movie",), _movie)
    store.update("today", ("screening",), lambda c: None)


def test_not_ready_until_both_scopes():
    store = SnapshotStore()
    with pytest.raises(SnapshotNotReady):
        with store.reader():
            pass
    store.update("other", ("movie",), _movie)
    assert not store.is_ready()
    store.update("today", ("screening",), lambda c: None)
    assert store.is_ready() and store.as_of() is not None


def test_reader_can_select_but_not_write():
    store = SnapshotStore()
    _ready(store)
    with store.reader() as conn:
        assert conn.execute("SELECT count(*) FROM movie").fetchone()[0] == 1
        for sql in (
            "INSERT INTO movie (movie_id, title) VALUES (9, 'x')",
            "DELETE FROM movie",
            "DROP TABLE movie",
            "PRAGMA query_only = OFF",
            "ATTACH DATABASE ':memory:' AS other",
            "CREATE TABLE t (a)",
        ):
            with pytest.raises(sqlite3.DatabaseError):
                conn.execute(sql)
        assert conn.execute("SELECT count(*) FROM movie").fetchone()[0] == 1


def test_update_copies_previous_and_adds():
    store = SnapshotStore()
    _ready(store)
    store.update("other", ("movie",), lambda c: _movie(c, 2))
    with store.reader() as conn:
        assert conn.execute("SELECT count(*) FROM movie").fetchone()[0] == 2


def test_failed_update_keeps_previous():
    store = SnapshotStore()
    _ready(store)

    def boom(conn):
        conn.execute("DELETE FROM movie")
        raise RuntimeError("x")

    with pytest.raises(RuntimeError):
        store.update("other", ("movie",), boom)
    with store.reader() as conn:
        assert conn.execute("SELECT count(*) FROM movie").fetchone()[0] == 1


def test_old_generation_closed_after_inflight_ends():
    store = SnapshotStore()
    _ready(store)
    with store.reader() as old:
        store.update("other", ("movie",), lambda c: _movie(c, 2))
        # 진행 중이라 아직 열려 있다
        assert old.execute("SELECT count(*) FROM movie").fetchone()[0] == 1
    with pytest.raises(sqlite3.ProgrammingError):
        old.execute("SELECT 1")


def test_unknown_scope():
    with pytest.raises(ValueError):
        SnapshotStore().update("x", (), lambda c: None)


def test_age_seconds_uses_start_when_never_loaded():
    now = [kst(2026, 11, 6, 12, 0)]
    store = SnapshotStore(clock=lambda: now[0])
    now[0] = kst(2026, 11, 6, 12, 5)
    assert store.age_seconds("today") == 300
    store.update("today", ("screening",), lambda c: None)
    assert store.age_seconds("today") == 0


def test_reader_from_other_thread():
    store = SnapshotStore()
    _ready(store)
    out = []

    def work():
        with store.reader() as conn:
            out.append(conn.execute("SELECT count(*) FROM movie").fetchone()[0])

    t = threading.Thread(target=work)
    t.start()
    t.join()
    assert out == [1]
