"""Java libs/common-*과 같은 규칙인지 보는 테스트 (TraceIds·GlobalExceptionHandler·logback)."""

import logging

from seatsac_ai.common.logging import PlainFormatter
from seatsac_ai.common.metrics import chat_path_label
from seatsac_ai.common.trace import is_valid_trace_id, resolve_trace_id, trace_id_var


def test_all_zero_trace_id_is_invalid_and_replaced():
    zero = "0" * 32
    assert not is_valid_trace_id(zero)
    assert resolve_trace_id(zero) != zero


def test_uppercase_or_hyphenated_trace_ids_are_invalid():
    assert not is_valid_trace_id("0123456789ABCDEF0123456789ABCDEF")
    assert not is_valid_trace_id("01234567-89ab-cdef-0123-456789abcdef")


def test_method_not_allowed_matches_java_shape(client, user_headers):
    r = client.delete("/ai/presets", headers=user_headers)
    assert r.status_code == 405
    body = r.json()
    assert body["code"] == "METHOD_NOT_ALLOWED"
    assert body["details"] == [
        {"field": "method", "value": "DELETE", "reason": "METHOD_NOT_ALLOWED"}
    ]


def test_broken_json_is_malformed_body(client, user_headers):
    r = client.post(
        "/ai/chat",
        content=b"{not json",
        headers={**user_headers, "content-type": "application/json"},
    )
    assert r.status_code == 400
    assert r.json()["code"] == "VALIDATION_FAILED"
    assert r.json()["details"] == [{"reason": "MALFORMED_BODY"}]


def test_missing_body_is_malformed_body(client, user_headers):
    r = client.post("/ai/chat", headers=user_headers)
    assert r.status_code == 400
    assert r.json()["details"] == [{"reason": "MALFORMED_BODY"}]


def test_openapi_docs_are_not_exposed(client):
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404


def test_plain_formatter_for_local_profile():
    token = trace_id_var.set("0123456789abcdef0123456789abcdef")
    try:
        record = logging.LogRecord("seatsac_ai.x", logging.INFO, __file__, 1, "hi", (), None)
        line = PlainFormatter().format(record)
    finally:
        trace_id_var.reset(token)
    assert "INFO  [0123456789abcdef0123456789abcdef] seatsac_ai.x - hi" in line


def test_chat_path_label_maps_response_path_to_metric_label():
    assert chat_path_label("CACHE_EXACT") == "cache_exact"
    assert chat_path_label("OUT_OF_SCOPE") == "out_of_scope"


def test_chat_path_label_rejects_unknown_value():
    import pytest

    with pytest.raises(ValueError):
        chat_path_label("SOMETHING_ELSE")
