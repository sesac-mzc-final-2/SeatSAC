import json
import logging

import pytest

from seatsac_ai.common.logging import JsonFormatter
from seatsac_ai.common.trace import trace_id_var, user_id_var
from seatsac_ai.config import Settings


def test_prometheus_exposes_http_metric_with_application_label(client, user_headers):
    client.get("/ai/presets", headers=user_headers)
    text = client.get("/actuator/prometheus").text
    expected = (
        "http_server_requests_seconds_count"
        '{application="ai",method="GET",status="200",uri="/ai/presets"}'
    )
    assert expected in text


def test_unmatched_path_uses_not_found_uri_label(client):
    client.get("/definitely/not/here/123")
    text = client.get("/actuator/prometheus").text
    assert 'uri="NOT_FOUND"' in text


def test_ai_metric_names_are_registered(client):
    text = client.get("/actuator/prometheus").text
    # 값이 아직 없어도 이름·타입은 노출된다(Counter는 _total로 보인다)
    for name in (
        "ai_chat_total",
        "ai_tokens_total",
        "ai_cache_requests_total",
        "ai_llm_call_seconds",
        "ai_sql_exec_seconds",
        "ai_rejected_total",
        "ai_snapshot_age_seconds",
        "ai_quota_rejected_total",
    ):
        assert f"# TYPE {name}" in text, name


def test_label_series_are_pre_registered_at_zero(client):
    text = client.get("/actuator/prometheus").text
    assert 'ai_chat_total{path="cache_semantic",result="ok"} 0.0' in text
    assert 'ai_cache_requests_total{layer="exact",result="hit"} 0.0' in text
    assert 'ai_quota_rejected_total{window="day"} 0.0' in text


def test_json_log_has_contract_fields():
    token_t = trace_id_var.set("0123456789abcdef0123456789abcdef")
    token_u = user_id_var.set("user-1")
    try:
        record = logging.LogRecord("x", logging.INFO, __file__, 1, "hello %s", ("world",), None)
        record.fields = {"path": "preset", "latencyMs": 38}
        line = JsonFormatter().format(record)
    finally:
        trace_id_var.reset(token_t)
        user_id_var.reset(token_u)

    assert "\n" not in line
    data = json.loads(line)
    assert data["service"] == "ai"
    assert data["level"] == "INFO"
    assert data["traceId"] == "0123456789abcdef0123456789abcdef"
    assert data["userId"] == "user-1"
    assert data["message"] == "hello world"
    assert data["logger"] == "x"
    assert data["path"] == "preset" and data["latencyMs"] == 38
    assert data["timestamp"].endswith("+09:00")


def test_settings_defaults_match_runtime_contract():
    s = Settings(_env_file=None)
    assert s.ai_model == "claude-sonnet-5-5"
    assert (s.llm_timeout_seconds, s.llm_max_calls_per_query) == (25, 3)
    assert (s.sql_timeout_seconds, s.max_rows) == (5, 500)
    assert (s.snapshot_today_interval, s.snapshot_other_interval, s.snapshot_days) == (60, 600, 7)
    assert (s.cache_ttl, s.session_ttl) == (3600, 1800)
    assert (s.semantic_cache_enabled, s.semantic_threshold) == (True, 0.92)
    assert (s.limit_per_minute, s.limit_per_day) == (10, 100)
    assert s.anthropic_api_key is None


def test_settings_read_env_names_and_durations(monkeypatch):
    monkeypatch.setenv("APP_AI_CACHE_TTL", "2h")
    monkeypatch.setenv("APP_AI_SNAPSHOT_TODAY_INTERVAL", "30s")
    monkeypatch.setenv("APP_AI_SEMANTIC_CACHE_ENABLED", "false")
    monkeypatch.setenv("APP_ENV", "staging")
    # 실제 Anthropic 키 형식은 gitleaks가 막으므로 테스트 값도 그 형식을 쓰지 않는다
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-not-a-real-key")
    s = Settings(_env_file=None)
    assert s.cache_ttl == 7200
    assert s.snapshot_today_interval == 30
    assert s.semantic_cache_enabled is False
    assert s.app_env == "staging"
    # 비밀값은 repr·로그에 드러나지 않는다
    assert "test-not-a-real-key" not in repr(s)
    assert s.anthropic_api_key.get_secret_value() == "test-not-a-real-key"


def test_settings_reject_unknown_profile(monkeypatch):
    monkeypatch.setenv("APP_ENV", "prod")
    with pytest.raises(ValueError):
        Settings(_env_file=None)


def test_settings_reject_bad_duration(monkeypatch):
    monkeypatch.setenv("APP_AI_CACHE_TTL", "soon")
    with pytest.raises(ValueError):
        Settings(_env_file=None)
