from datetime import datetime
from unittest.mock import Mock, call

import pytest

from seatsac_ai.demand.repository import MySqlDemandRepository

START = datetime(2026, 11, 23, 15)
END = datetime(2026, 11, 24, 15)


@pytest.fixture
def database():
    conn = Mock()
    cursor = conn.cursor.return_value
    connect = Mock(return_value=conn)
    return MySqlDemandRepository(connect), connect, conn, cursor


@pytest.mark.parametrize(
    "slots, expected_json", [(None, None), ({"등급": "전체"}, '{"등급": "전체"}')]
)
def test_insert_binds_only_sanitized_data_and_commits(database, slots, expected_json):
    repo, connect, conn, cursor = database
    reason = "주차 요금 '문의'"
    repo.insert(
        category="site_feature", reason=reason, question_hash="abc", slots=slots, created_at=START
    )

    connect.assert_called_once_with()
    sql, params = cursor.execute.call_args.args
    assert "(category, reason, question_hash, slots, created_at)" in sql
    assert "VALUES (%s, %s, %s, %s, %s)" in sql
    assert reason not in sql
    assert params == ("site_feature", reason, "abc", expected_json, START)
    assert conn.mock_calls[-3:] == [call.cursor().close(), call.commit(), call.close()]
    conn.rollback.assert_not_called()


@pytest.mark.parametrize("stage", ["query", "commit"])
def test_insert_rolls_back_and_closes_on_failure(database, stage):
    repo, _, conn, cursor = database
    failing_operation = cursor.execute if stage == "query" else conn.commit
    failing_operation.side_effect = RuntimeError("DB failure")

    with pytest.raises(RuntimeError, match="DB failure"):
        repo.insert(
            category="unknown", reason=None, question_hash="abc", slots=None, created_at=START
        )

    cursor.close.assert_called_once_with()
    assert conn.mock_calls[-2:] == [call.rollback(), call.close()]
    if stage == "query":
        conn.commit.assert_not_called()


def test_stats_binds_same_half_open_period_in_one_read_only_snapshot(database):
    repo, connect, conn, cursor = database
    cursor.fetchone.return_value = (4,)
    cursor.fetchall.side_effect = [
        [("site_feature", 3), ("unknown", 1)],
        [("site_feature", "주차 요금", 2)],
    ]

    assert repo.stats(START, END, 1) == {
        "total": 4,
        "byCategory": [
            {"category": "site_feature", "count": 3},
            {"category": "unknown", "count": 1},
        ],
        "top": [{"category": "site_feature", "reason": "주차 요금", "count": 2}],
    }

    connect.assert_called_once_with()
    statements = cursor.execute.call_args_list
    assert statements[:2] == [
        call("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"),
        call("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY"),
    ]
    assert len(statements) == 5
    for statement in statements[2:]:
        assert "created_at >= %s AND created_at < %s" in statement.args[0]
    assert [statement.args[1] for statement in statements[2:]] == [
        (START, END),
        (START, END),
        (START, END, 1),
    ]
    assert "ORDER BY demand_count DESC, exact_category ASC" in statements[3].args[0]
    top_sql = statements[4].args[0]
    assert "reason IS NOT NULL AND reason <> ''" in top_sql
    assert "GROUP BY exact_category, reason" in top_sql
    assert "ORDER BY demand_count DESC, exact_category ASC, reason ASC" in top_sql
    assert "LIMIT %s" in top_sql
    assert conn.mock_calls[-3:] == [call.cursor().close(), call.rollback(), call.close()]
    conn.commit.assert_not_called()


def test_stats_returns_zero_and_empty_lists(database):
    repo, _, _, cursor = database
    cursor.fetchone.return_value = (0,)
    cursor.fetchall.return_value = []
    assert repo.stats(START, END, 20) == {"total": 0, "byCategory": [], "top": []}


def test_stats_rolls_back_and_closes_on_query_failure(database):
    repo, _, conn, cursor = database
    cursor.fetchone.return_value = (4,)
    cursor.execute.side_effect = [None, None, None, RuntimeError("DB failure")]
    with pytest.raises(RuntimeError, match="DB failure"):
        repo.stats(START, END, 20)
    assert conn.mock_calls[-3:] == [call.cursor().close(), call.rollback(), call.close()]
