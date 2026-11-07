"""앱 기동(lifespan) ↔ 스냅샷 ↔ readiness 연결. 가짜 공개 API로 실제 앱을 띄운다."""

import asyncio
import threading
import time

import httpx
from fastapi.testclient import TestClient

import seatsac_ai.snapshot as snapshot_pkg
from seatsac_ai.config import Settings
from seatsac_ai.main import create_app
from seatsac_ai.snapshot import scheduler as scheduler_mod
from seatsac_ai.snapshot.client import PublicApiClient

from .snapshot_fakes import movie_detail, movie_summary, screening, theater


def _fake_api(gate: threading.Event, fail_first: int = 0):
    calls = {"n": 0}

    async def handler(request: httpx.Request) -> httpx.Response:
        while not gate.is_set():  # 열릴 때까지 응답을 미룬다(적재 전 상태를 보려고)
            await asyncio.sleep(0.01)
        calls["n"] += 1
        if calls["n"] <= fail_first:
            return httpx.Response(503)
        path = request.url.path.removeprefix("/api")
        if path == "/movies":
            return httpx.Response(200, json={"content": [movie_summary(1)], "totalPages": 1})
        if path.startswith("/movies/"):
            return httpx.Response(200, json=movie_detail(1))
        if path == "/theaters":
            return httpx.Response(200, json={"items": [theater(10)]})
        if path == "/screenings":
            return httpx.Response(200, json={"items": [screening(1)]})
        return httpx.Response(404)

    return handler


def _install(monkeypatch, handler):
    def factory(base_url):
        return PublicApiClient(base_url, httpx.AsyncClient(transport=httpx.MockTransport(handler)))

    monkeypatch.setattr(snapshot_pkg, "PublicApiClient", factory)


def _wait_ready(client, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if client.get("/actuator/health/readiness").status_code == 200:
            return True
        time.sleep(0.02)
    return False


def _settings():
    return Settings(_env_file=None, PUBLIC_API_BASE_URL="https://x.test/api")


def test_readiness_503_until_first_load_then_200_and_cleanup(monkeypatch):
    gate = threading.Event()
    _install(monkeypatch, _fake_api(gate))
    app = create_app(_settings())
    with TestClient(app) as client:
        r = client.get("/actuator/health/readiness")
        assert r.status_code == 503 and r.json()["components"]["snapshot"] == "DOWN"
        gate.set()
        assert _wait_ready(client)
    store = app.state.snapshot.store
    assert store.is_ready()
    # 종료 때 정리됐다: 현재 세대가 닫혀 더 이상 빌려줄 연결이 없다
    assert store._current is None


def test_api_down_at_startup_retries_until_ready(monkeypatch):
    monkeypatch.setattr(scheduler_mod, "_FIRST_LOAD_RETRY_SECONDS", 0.05)
    monkeypatch.setattr("seatsac_ai.snapshot.client._RETRY_DELAY_SECONDS", 0)
    gate = threading.Event()
    gate.set()
    _install(monkeypatch, _fake_api(gate, fail_first=6))
    with TestClient(create_app(_settings())) as client:
        assert _wait_ready(client)


def test_staging_without_url_is_not_ready():
    app = create_app(Settings(_env_file=None, APP_ENV="staging"))
    with TestClient(app) as client:
        assert client.get("/actuator/health/readiness").status_code == 503
