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
