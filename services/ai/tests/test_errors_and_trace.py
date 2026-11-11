import re

from seatsac_ai.common.errors import ErrorCode, quota_exceeded
from seatsac_ai.common.trace import TRACE_ID_PATTERN

TRACE = re.compile(TRACE_ID_PATTERN.pattern)


def assert_error_v1(body: dict, code: str) -> None:
    """error.v1.json 규칙: 필수 4개, 추가 필드 금지, traceId 32자리, details 배열."""
    assert set(body) == {"code", "message", "traceId", "details"}
    assert body["code"] == code
    assert re.fullmatch(r"[A-Z_]+", body["code"])
    assert isinstance(body["message"], str)
    assert TRACE.fullmatch(body["traceId"])
    assert isinstance(body["details"], list)
    for d in body["details"]:
        assert set(d) <= {"field", "value", "reason"}


def test_unknown_path_is_404_not_found_in_error_v1_shape(client):
    r = client.get("/nope")
    assert r.status_code == 404
    assert_error_v1(r.json(), "NOT_FOUND")
    assert r.headers["X-Trace-Id"] == r.json()["traceId"]


def test_validation_error_is_400_without_echoing_input(client, user_headers):
    r = client.post("/ai/chat", json={"message": "x" * 301}, headers=user_headers)
    assert r.status_code == 400
    body = r.json()
    assert_error_v1(body, "VALIDATION_FAILED")
    assert body["details"][0]["field"] == "message"
    assert "xxxxxxxx" not in r.text


def test_empty_message_is_400(client, user_headers):
    r = client.post("/ai/chat", json={"message": ""}, headers=user_headers)
    assert r.status_code == 400


def test_missing_user_header_is_401_token_required(client):
    r = client.get("/ai/presets")
    assert r.status_code == 401
    body = r.json()
    assert_error_v1(body, "UNAUTHORIZED")
    assert body["details"] == [{"reason": "TOKEN_REQUIRED"}]


def test_inbound_trace_id_is_kept(client):
    trace = "0123456789abcdef0123456789abcdef"
    r = client.get("/nope", headers={"X-Trace-Id": trace})
    assert r.headers["X-Trace-Id"] == trace
    assert r.json()["traceId"] == trace


def test_invalid_inbound_trace_id_is_replaced(client):
    r = client.get("/nope", headers={"X-Trace-Id": "not-a-trace-id"})
    assert TRACE.fullmatch(r.headers["X-Trace-Id"])
    assert r.json()["traceId"] == r.headers["X-Trace-Id"]


def test_missing_trace_id_is_generated(client):
    r = client.get("/actuator/health/liveness")
    assert TRACE.fullmatch(r.headers["X-Trace-Id"])


def test_unhandled_exception_becomes_500_internal_error(app, client):
    @app.get("/boom")
    async def boom():
        raise RuntimeError("secret detail must not leak")

    r = client.get("/boom")
    assert r.status_code == 500
    assert_error_v1(r.json(), "INTERNAL_ERROR")
    assert "secret detail" not in r.text
    assert "X-Trace-Id" in r.headers


def test_quota_error_matches_spec_details_and_retry_after():
    err = quota_exceeded("PER_MINUTE", 10, 42)
    assert err.status == 429
    assert err.code is ErrorCode.AI_QUOTA_EXCEEDED
    assert err.details[0].to_dict() == {"field": "quota", "value": "10/min", "reason": "PER_MINUTE"}
    assert err.headers == {"Retry-After": "42"}


def test_chat_without_snapshot_returns_snapshot_not_ready(client, user_headers):
    r = client.post("/ai/chat", json={"message": "오늘 20시 이후 회차"}, headers=user_headers)
    assert r.status_code == 503
    assert_error_v1(r.json(), "AI_SNAPSHOT_NOT_READY")


def test_presets_lists_twelve_buttons(client, user_headers):
    r = client.get("/ai/presets", headers=user_headers)
    assert r.status_code == 200
    items = r.json()["items"]
    assert len(items) == 12
    assert set(items[0]) == {"presetId", "title", "example"}
    # 스냅샷이 없으면 영화 예시는 계약 예시처럼 "영화 제목"
    examples = {i["presetId"]: i["example"] for i in items}
    assert examples["movie_theaters"] == "영화 제목 어디서 몇 시에 해?"
    assert all("{movie}" not in e for e in examples.values())


def _presets_with_snapshot(client, user_headers, now: str) -> dict[str, str]:
    from datetime import datetime
    from types import SimpleNamespace

    from seatsac_ai.snapshot.mapper import KST

    from .sql_fixtures import loaded_store

    fixed = datetime.strptime(now, "%Y-%m-%d %H:%M").replace(tzinfo=KST)
    client.app.state.snapshot = SimpleNamespace(store=loaded_store(clock=lambda: fixed))
    try:
        items = client.get("/ai/presets", headers=user_headers).json()["items"]
    finally:
        client.app.state.snapshot = None
    return {i["presetId"]: i["example"] for i in items}


def test_presets_fill_movie_example_from_the_snapshot(client, user_headers):
    """#92 리뷰: 실제 시계가 아니라 스토어 시계로 — NOW(11/17 19:40) 기준 정확한 영화."""
    from .sql_fixtures import NOW

    examples = _presets_with_snapshot(client, user_headers, NOW)
    assert len(examples) == 12
    assert examples["movie_theaters"] == "철의 심장 어디서 몇 시에 해?"  # 이후 회차 3건으로 최다


def test_presets_drop_movie_buttons_when_no_screening_is_left(client, user_headers):
    """스냅샷은 준비됐는데 이후 회차가 없으면 '영화 제목' 버튼을 내보내지 않는다(#92 리뷰)."""
    examples = _presets_with_snapshot(client, user_headers, "2027-01-01 00:00")
    assert len(examples) == 9
    assert {"movie_theaters", "movie_next_screening", "movie_runtime"}.isdisjoint(examples)
