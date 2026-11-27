import asyncio
import logging
import ssl
import threading
import time
from datetime import datetime
from unittest.mock import Mock

import pytest

from seatsac_ai.config import Settings
from seatsac_ai.demand import service
from seatsac_ai.demand.service import DemandService, DemandUnavailable, build_demand_service

FIELDS = {"category": "site_feature", "reason": "이용 안내 요청", "questionHash": "a" * 16}


@pytest.mark.parametrize(
    ("error", "category"), [(TimeoutError("private"), "TIMEOUT"),
                            (RuntimeError("private"), "INTERNAL")],
)
def test_db_failure_logs_category_without_exception_details(caplog, error, category):
    demand = DemandService(Mock(stats=Mock(side_effect=error)))
    try:
        with caplog.at_level(logging.WARNING), pytest.raises(DemandUnavailable):
            asyncio.run(demand.stats(datetime(2026, 11, 23), datetime(2026, 11, 24), 20))
        (record,) = caplog.records
        assert record.fields["errorCategory"] == category
        assert "private" not in caplog.text
    finally:
        demand.close()


@pytest.mark.parametrize("timeout", [0, 1])
def test_record_timeout_keeps_timeout_category_in_all_warnings(caplog, timeout):
    demand = DemandService(Mock(insert=Mock(side_effect=TimeoutError("private"))))
    try:
        with caplog.at_level(logging.WARNING):
            asyncio.run(demand.record(FIELDS, timeout=timeout))
        assert caplog.records
        assert all(r.fields["errorCategory"] == "TIMEOUT" for r in caplog.records)
        assert "private" not in caplog.text
    finally:
        demand.close()


def test_disabled_or_incomplete_settings_never_open_a_connection(monkeypatch):
    connect = Mock()
    monkeypatch.setattr(service.pymysql, "connect", connect)
    for settings in (
        Settings(_env_file=None),
        Settings(_env_file=None, unmet_demand_enabled=True),
        Settings(_env_file=None, unmet_demand_enabled=True, db_host="example.invalid"),
        Settings(_env_file=None, unmet_demand_enabled=True, db_host="example.invalid",
                 db_password="", app_env="local"),
        Settings(_env_file=None, unmet_demand_enabled=True, db_host="example.invalid",
                 db_password="test-only", app_env="staging"),  # CA 필수
    ):
        assert build_demand_service(settings) is None
    connect.assert_not_called()


def test_tls_is_verified_and_connection_uses_only_ai_app(monkeypatch):
    connect = Mock()
    monkeypatch.setattr(service.pymysql, "connect", connect)
    settings = Settings(_env_file=None, unmet_demand_enabled=True, db_host="example.invalid",
                        db_password="test-only", db_port=3307)
    demand = build_demand_service(settings)
    try:
        # 연결 factory만 검사: 실제 네트워크 접근은 mock으로 막는다.
        demand._repository._connect()
        kwargs = connect.call_args.kwargs
        assert kwargs["host"] == "example.invalid" and kwargs["port"] == 3307
        assert kwargs["user"] == "ai_app" and kwargs["database"] == "ai_db"
        assert kwargs["ssl"].check_hostname is True
        assert kwargs["ssl"].verify_mode == ssl.CERT_REQUIRED
        assert kwargs["connect_timeout"] == kwargs["read_timeout"] == kwargs["write_timeout"] == 1
        assert "MAX_EXECUTION_TIME = 1500" in kwargs["init_command"]
        assert "time_zone = '+00:00'" in kwargs["init_command"]
        assert "test-only" not in repr(settings)
    finally:
        demand.close()


def test_bad_ca_does_not_leak_filename_or_password(monkeypatch, caplog):
    def fail(**kwargs):
        raise OSError("secret-path secret-password")

    monkeypatch.setattr(service.ssl, "create_default_context", fail)
    with caplog.at_level(logging.WARNING):
        result = build_demand_service(Settings(
            _env_file=None, unmet_demand_enabled=True, db_host="example.invalid",
            db_password="secret-password", db_ssl_ca="secret-path",
        ))
    assert result is None
    assert "secret-path" not in caplog.text and "secret-password" not in caplog.text


def test_record_only_sends_whitelisted_fields_and_utc_time():
    repository = Mock()
    demand = DemandService(repository)
    try:
        asyncio.run(demand.record({**FIELDS, "questionLength": 20, "reasonDropped": False}))
        sent = repository.insert.call_args.kwargs
        assert set(sent) == {"category", "reason", "question_hash", "slots", "created_at"}
        assert sent["slots"] == {} and sent["question_hash"] == FIELDS["questionHash"]
        assert sent["created_at"].tzinfo is None
        assert sent["created_at"].microsecond % 1000 == 0
        assert sent["category"] == FIELDS["category"]
    finally:
        demand.close()


def test_record_floors_fractional_milliseconds_at_kst_midnight(monkeypatch):
    class FixedDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return cls(2026, 11, 23, 14, 59, 59, 999999, tzinfo=tz)

    monkeypatch.setattr(service, "datetime", FixedDatetime)
    repository = Mock()
    demand = DemandService(repository)
    try:
        asyncio.run(demand.record(FIELDS))
        assert repository.insert.call_args.kwargs["created_at"] == datetime(
            2026, 11, 23, 14, 59, 59, 999000
        )
    finally:
        demand.close()


def test_db_failure_keeps_record_best_effort_and_redacts_errors(caplog):
    repository = Mock()
    repository.insert.side_effect = RuntimeError("private-address password raw-question")
    demand = DemandService(repository)
    try:
        with caplog.at_level(logging.WARNING):
            asyncio.run(demand.record(FIELDS))
        assert "RuntimeError" in caplog.text
        assert all(
            value not in caplog.text for value in ("private-address", "password", "raw-question")
        )
    finally:
        demand.close()


def test_stats_failure_becomes_sanitized_service_error():
    repository = Mock()
    repository.stats.side_effect = RuntimeError("secret")
    demand = DemandService(repository)
    try:
        with pytest.raises(DemandUnavailable) as caught:
            asyncio.run(demand.stats(datetime(2026, 11, 23), datetime(2026, 11, 24), 20))
        assert "secret" not in str(caught.value)
    finally:
        demand.close()


def test_timeout_returns_without_waiting_for_thread_and_holds_capacity(monkeypatch):
    release = threading.Event()
    entered = threading.Event()

    def blocked(*args):
        entered.set()
        release.wait(3)
        return {"total": 0, "byCategory": [], "top": []}

    repository = Mock(stats=blocked)
    monkeypatch.setattr(service, "MAX_WORKERS", 1)
    monkeypatch.setattr(service, "STATS_TIMEOUT", 0.05)
    demand = DemandService(repository)

    async def check():
        started = time.monotonic()
        with pytest.raises(DemandUnavailable):
            await demand.stats(datetime(2026, 11, 23), datetime(2026, 11, 24), 20)
        assert entered.is_set() and time.monotonic() - started < 0.5
        # 응답 취소 후에도 스레드는 실행 중이다. 추가 작업은 즉시 거부(대기열 없음).
        with pytest.raises(DemandUnavailable):
            await demand.stats(datetime(2026, 11, 23), datetime(2026, 11, 24), 20)
        assert not demand._slots.acquire(blocking=False)
        release.set()
        for _ in range(100):
            if demand._slots.acquire(blocking=False):
                demand._slots.release()
                break
            await asyncio.sleep(0.01)
        else:
            pytest.fail("완료된 DB 작업의 슬롯이 반환되지 않음")
        result = await demand.stats(datetime(2026, 11, 23), datetime(2026, 11, 24), 20)
        assert result["total"] == 0

    try:
        asyncio.run(check())
    finally:
        release.set()
        demand.close()


def test_app_wires_demand_without_making_db_a_snapshot_readiness_dependency(monkeypatch):
    from types import SimpleNamespace
    from unittest.mock import AsyncMock

    from fastapi.testclient import TestClient

    from seatsac_ai import main

    from .llm_fakes import ScriptedLlm, tool
    from .sql_fixtures import loaded_store

    snapshot = SimpleNamespace(
        store=loaded_store(), start=Mock(), stop=AsyncMock(), refresher=None,
    )
    demand = Mock(record=AsyncMock(), stats=AsyncMock(side_effect=DemandUnavailable))
    monkeypatch.setattr(main, "build_snapshot", lambda *_: snapshot)
    monkeypatch.setattr(main, "build_demand_service", lambda _: demand)
    settings = Settings(_env_file=None, unmet_demand_enabled=True,
                        public_api_base_url="https://example.invalid/api")
    app = main.create_app(
        settings,
        llm=ScriptedLlm(tool("out_of_scope", category="site_feature", reason="시설 안내")),
    )
    assert app.state.chat._store is snapshot.store and app.state.chat._demand is demand
    with TestClient(app) as client:
        assert client.get("/actuator/health/readiness").status_code == 200
        assert client.get("/internal/ai/unmet-demand/stats").status_code == 503
        chat = client.post("/ai/chat", json={"message": "합성 극장에 어떤 시설이 있어요?"},
                           headers={"X-User-Id": "11111111-2222-3333-4444-555555555555"})
        assert chat.status_code == 200 and chat.json()["path"] == "OUT_OF_SCOPE"
        demand.record.assert_awaited_once()
    demand.close.assert_called_once()
