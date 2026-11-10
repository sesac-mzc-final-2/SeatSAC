import asyncio

import pytest

from seatsac_ai.sql.executor import (
    SQL_ERROR,
    TIMEOUT,
    SqlExecutionError,
    execute,
    execute_async,
)
from seatsac_ai.sql.validator import ValidatedSql, validate

from .sql_fixtures import NOW, TODAY, loaded_store

AFTER_20 = """
SELECT m.title AS movie_title, t.name AS theater_name, s.starts_at, s.remaining_seats
FROM screening s
JOIN movie m ON m.movie_id = s.movie_id
JOIN theater t ON t.theater_id = s.theater_id
WHERE s.business_date = :today AND s.starts_at >= :today || ' 20:00' AND s.starts_at > :now
ORDER BY s.starts_at
"""


def test_runs_and_describes_columns():
    result = execute(loaded_store(), validate(AFTER_20), {"today": TODAY, "now": NOW})
    # 20:00 · 21:00 · 22:30 · 다음 날 00:30(오늘 영업일 심야)
    assert [r[2] for r in result.rows] == [
        "2026-11-17 20:00",
        "2026-11-17 21:00",
        "2026-11-17 22:30",
        "2026-11-18 00:30",
    ]
    assert result.columns == [
        {"name": "movie_title", "type": "text"},
        {"name": "theater_name", "type": "text"},
        {"name": "starts_at", "type": "text"},
        {"name": "remaining_seats", "type": "integer"},
    ]
    assert result.row_count == 4 and not result.truncated and result.as_of is not None


def test_truncated_when_our_limit_is_reached():
    store = loaded_store()
    result = execute(store, validate("SELECT screening_id FROM screening", max_rows=3), max_rows=3)
    assert result.row_count == 3 and result.truncated


def test_not_truncated_when_user_asked_for_few():
    result = execute(loaded_store(), validate("SELECT screening_id FROM screening LIMIT 2"))
    assert result.row_count == 2 and not result.truncated


def test_sql_error_is_reported_for_retry():
    with pytest.raises(SqlExecutionError) as caught:
        execute(loaded_store(), validate("SELECT no_such_column FROM movie"))
    assert caught.value.reason == SQL_ERROR and "no_such_column" in caught.value.message


def test_slow_query_is_stopped():
    # 검증기를 거치지 않은 무거운 교차 조인(9^6 ≈ 53만 행 집계)으로 시간 상한만 확인한다
    heavy = ValidatedSql(
        sql="SELECT count(*) FROM screening a, screening b, screening c, screening d, "
        "screening e, screening f",
        tables=frozenset({"screening"}),
        limit_capped=False,
    )
    with pytest.raises(SqlExecutionError) as caught:
        execute(loaded_store(), heavy, timeout_seconds=0.001)
    assert caught.value.reason == TIMEOUT


def test_write_is_blocked_even_if_validator_is_bypassed():
    sneaky = ValidatedSql(sql="DELETE FROM movie", tables=frozenset(), limit_capped=False)
    store = loaded_store()
    with pytest.raises(SqlExecutionError):
        execute(store, sneaky)
    assert execute(store, validate("SELECT count(*) AS c FROM movie")).rows == [[4]]


def test_async_wrapper():
    result = asyncio.run(execute_async(loaded_store(), validate("SELECT title FROM movie")))
    assert result.row_count == 4


def test_huge_string_cannot_be_built():
    """#91 리뷰: printf 한 번으로 수백 MB 문자열 → OOM. 연결의 길이 상한(1MB)으로 막는다.
    SQLite 버전에 따라 오류(SQL_ERROR) 또는 NULL 이 된다 — 둘 다 메모리를 크게 쓰지 않는다."""
    store = loaded_store()
    query = validate("SELECT length(printf('%.*c', 10000000, 'x')) AS n")
    try:
        result = execute(store, query)
    except SqlExecutionError as exc:
        assert exc.reason == SQL_ERROR
    else:
        assert result.rows == [[None]]
    # 1MB 아래는 그대로 된다
    small = validate("SELECT length(printf('%.*c', 1000, 'x')) AS n")
    assert execute(store, small).rows == [[1000]]
