import asyncio
from datetime import date

import httpx
import pytest

from seatsac_ai.snapshot import client as client_mod
from seatsac_ai.snapshot.client import PublicApiClient, SnapshotFetchError


def make(handler):
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return PublicApiClient("https://x.test/api/", http)


@pytest.fixture(autouse=True)
def _fast(monkeypatch):
    monkeypatch.setattr(client_mod, "_RETRY_DELAY_SECONDS", 0)


def test_movies_pages_all():
    calls = []

    def handler(req):
        page = int(req.url.params["page"])
        calls.append((req.url.path, dict(req.url.params)))
        return httpx.Response(200, json={"content": [{"movieId": page}], "totalPages": 3})

    items = asyncio.run(make(handler).list_movies())
    assert [i["movieId"] for i in items] == [0, 1, 2]
    assert calls[0][0] == "/api/movies"
    assert calls[0][1]["status"] == "NOW_SHOWING" and calls[0][1]["size"] == "50"


def test_theaters_and_screenings_params():
    seen = []

    def handler(req):
        seen.append(req.url)
        return httpx.Response(200, json={"items": [{"a": 1}]})

    c = make(handler)
    assert asyncio.run(c.list_theaters()) == [{"a": 1}]
    assert asyncio.run(c.list_screenings(date(2026, 11, 6), 10)) == [{"a": 1}]
    assert seen[0].params["hasLayout"] == "true"
    assert seen[1].params["date"] == "2026-11-06" and seen[1].params["theaterId"] == "10"


def test_retry_once_on_5xx_then_ok():
    n = []

    def handler(req):
        n.append(1)
        return httpx.Response(503) if len(n) == 1 else httpx.Response(200, json={"items": []})

    assert asyncio.run(make(handler).list_theaters()) == []
    assert len(n) == 2


def test_5xx_twice_fails():
    with pytest.raises(SnapshotFetchError):
        asyncio.run(make(lambda r: httpx.Response(500)).list_theaters())


def test_4xx_no_retry():
    n = []

    def handler(req):
        n.append(1)
        return httpx.Response(404)

    with pytest.raises(SnapshotFetchError):
        asyncio.run(make(handler).get_movie(1))
    assert len(n) == 1


def test_transport_error_and_bad_json():
    def boom(req):
        raise httpx.ConnectError("x")

    with pytest.raises(SnapshotFetchError):
        asyncio.run(make(boom).list_theaters())
    with pytest.raises(SnapshotFetchError):
        asyncio.run(make(lambda r: httpx.Response(200, content=b"<html>")).list_theaters())


@pytest.mark.parametrize(
    "call, body",
    [
        ("theaters", {}),
        ("theaters", {"items": None}),
        ("screenings", {"oops": []}),
        ("movies", {"totalPages": 1}),
        ("movies", {"content": [], "totalPages": "1"}),
        ("movie", []),
    ],
)
def test_missing_required_field_is_failure_not_empty(call, body):
    c = make(lambda r: httpx.Response(200, json=body))
    run = {
        "theaters": c.list_theaters,
        "screenings": lambda: c.list_screenings(date(2026, 11, 6), 1),
        "movies": c.list_movies,
        "movie": lambda: c.get_movie(1),
    }[call]
    with pytest.raises(SnapshotFetchError):
        asyncio.run(run())
