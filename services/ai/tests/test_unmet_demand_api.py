import asyncio
from datetime import datetime

import pytest

from seatsac_ai.api import unmet_demand
from seatsac_ai.snapshot.mapper import KST

from .test_errors_and_trace import assert_error_v1

STATS_PATH = "/internal/ai/unmet-demand/stats"


class DemandStub:
    def __init__(self, result=None):
        self.calls = []
        self.result = result if result is not None else {"total": 0, "byCategory": [], "top": []}

    async def stats(self, start, end, limit):
        self.calls.append((start, end, limit))
        return self.result


def test_read_unmet_demand_stats_without_user_header_and_with_kst_bounds(client):
    result = {
        "total": 3,
        "byCategory": [{"category": "site_feature", "count": 3}],
        "top": [{"category": "site_feature", "reason": "시설 문의", "count": 2}],
    }
    store = client.app.state.demand = DemandStub(result)
    trace = "0123456789abcdef0123456789abcdef"
    response = client.get(
        STATS_PATH, params={"from": "2026-11-24", "to": "2026-11-24", "limit": 1},
        headers={"X-Trace-Id": trace},
    )

    assert response.status_code == 200
    assert response.json() == {"from": "2026-11-24", "to": "2026-11-24", **result}
    assert response.headers["X-Trace-Id"] == trace
    assert store.calls == [(datetime(2026, 11, 23, 15), datetime(2026, 11, 24, 15), 1)]


def test_default_start_and_limit_with_explicit_end_and_no_rows(client):
    store = client.app.state.demand = DemandStub()
    response = client.get(STATS_PATH, params={"to": "2026-11-24"})

    assert response.status_code == 200
    assert response.json() == {
        "from": "2026-11-18", "to": "2026-11-24", "total": 0, "byCategory": [], "top": [],
    }
    assert store.calls == [(datetime(2026, 11, 17, 15), datetime(2026, 11, 24, 15), 20)]


def test_default_period_ends_today_kst(client, monkeypatch):
    class FixedDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            assert tz == KST
            return cls(2026, 11, 24, 0, 30, tzinfo=tz)

    monkeypatch.setattr(unmet_demand, "datetime", FixedDatetime)
    client.app.state.demand = DemandStub()
    response = client.get(STATS_PATH)

    assert response.status_code == 200
    assert response.json()["from"] == "2026-11-18"
    assert response.json()["to"] == "2026-11-24"


def test_accepts_31_inclusive_days_and_max_limit(client):
    store = client.app.state.demand = DemandStub()
    response = client.get(
        STATS_PATH, params={"from": "2026-11-01", "to": "2026-12-01", "limit": 100},
    )

    assert response.status_code == 200
    assert store.calls == [(datetime(2026, 10, 31, 15), datetime(2026, 12, 1, 15), 100)]


@pytest.mark.parametrize(
    ("params", "field"),
    [
        ({"from": "private-input"}, "from"),
        ({"from": "20261124"}, "from"),
        ({"from": "2026-11-2"}, "from"),
        ({"from": "2026-11-24T00:00:00"}, "from"),
        ({"from": "0001-01-01", "to": "0001-01-02"}, "from"),
        ({"to": "2026-02-29"}, "to"),
        ({"to": "9999-12-31"}, "to"),
        ({"to": "0001-01-02"}, "from"),  # 기본 기간을 만들 수 없다.
        ({"from": "2026-11-25", "to": "2026-11-24"}, "from"),
        ({"from": "2026-11-01", "to": "2026-12-02"}, "from"),
        ({"limit": "0"}, "limit"),
        ({"limit": "101"}, "limit"),
        ({"limit": "1.0"}, "limit"),
        ({"limit": "-1"}, "limit"),
        ({"limit": "true"}, "limit"),
        ({"limit": ""}, "limit"),
    ],
)
def test_invalid_query_returns_400_without_echo_even_when_db_unconfigured(client, params, field):
    client.app.state.demand = None
    response = client.get(STATS_PATH, params=params)

    assert response.status_code == 400
    body = response.json()
    assert_error_v1(body, "VALIDATION_FAILED")
    assert body["details"][0]["field"] == field
    assert "value" not in body["details"][0]
    assert "private-input" not in response.text


def assert_db_unavailable(response):
    assert response.status_code == 503
    body = response.json()
    assert_error_v1(body, "SERVICE_UNAVAILABLE")
    assert body["details"] == [{"field": "upstream", "value": "ai_db"}]
    assert body["traceId"] == response.headers["X-Trace-Id"]


def test_db_unconfigured_returns_503(client):
    assert_db_unavailable(client.get(STATS_PATH))


def test_db_failure_returns_503_without_exposing_details(client, caplog):
    class UnavailableStore:
        async def stats(self, *_):
            raise OSError("private-db-info")

    client.app.state.demand = UnavailableStore()
    response = client.get(STATS_PATH)

    assert_db_unavailable(response)
    assert "private-db-info" not in response.text
    assert "private-db-info" not in caplog.text


def test_db_timeout_returns_503_instead_of_waiting_for_query(client, monkeypatch):
    class SlowStore:
        async def stats(self, *_):
            await asyncio.sleep(1)
            pytest.fail("must cancel the stats wait outside the deadline")

    client.app.state.demand = SlowStore()
    monkeypatch.setattr(unmet_demand, "_STATS_TIMEOUT_SECONDS", 0.01)

    assert_db_unavailable(client.get(STATS_PATH))
