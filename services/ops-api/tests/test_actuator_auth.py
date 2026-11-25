from __future__ import annotations

import re

from prometheus_client import REGISTRY

from tests.fakes import USER_SUB, admin_headers, token, user_headers

TRACE = re.compile(r"^[0-9a-f]{32}$")


def _error(response, status: int, code: str, reason: str | None = None) -> dict:
    assert response.status_code == status, response.text
    body = response.json()
    assert body["code"] == code
    assert TRACE.fullmatch(body["traceId"])
    assert isinstance(body["details"], list)
    assert body["traceId"] == response.headers["X-Trace-Id"]
    if reason is not None:
        assert body["details"][0]["reason"] == reason
    return body


def test_health_and_metrics(client):
    assert client.get("/actuator/health/liveness").json() == {"status": "UP"}
    assert client.get("/actuator/health/readiness").status_code == 200
    metrics = client.get("/actuator/prometheus").text
    assert 'http_server_requests_seconds_count{application="ops-api"' in metrics
    assert 'ops_action_total{action="stop",result="denied"} 0.0' in metrics


def test_no_token_is_401_token_required(client):
    _error(client.get("/ops/services"), 401, "UNAUTHORIZED", "TOKEN_REQUIRED")
    _error(
        client.get("/ops/services", headers={"Authorization": "Basic x"}),
        401,
        "UNAUTHORIZED",
        "TOKEN_REQUIRED",
    )


def test_invalid_tokens_are_401_invalid_token(client):
    for bad in (
        "not-a-jwt",
        token(exp_in=-120),  # 만료(leeway 30초 밖)
        token(token_use="id"),  # ID 토큰
        token(client_id="other-client"),  # 다른 앱 클라이언트
        token(issuer="https://evil.example/pool"),
        token(kid="unknown"),
    ):
        response = client.get("/ops/services", headers={"Authorization": f"Bearer {bad}"})
        _error(response, 401, "UNAUTHORIZED", "INVALID_TOKEN")


def test_non_admin_is_403(client):
    _error(client.get("/ops/services", headers=user_headers()), 403, "FORBIDDEN")


def test_jwks_down_is_503_not_401(client, jwk):
    jwk.fail_connection = True
    body = _error(client.get("/ops/services", headers=admin_headers()), 503, "SERVICE_UNAVAILABLE")
    assert body["details"][0] == {
        "field": "upstream",
        "value": "cognito",
        "reason": "JWKS_UNAVAILABLE",
    }


def test_forbidden_stop_is_audited_and_counted(client, caplog):
    before = _denied()
    caplog.set_level("INFO", logger="seatsac_ops.audit")
    _error(client.post("/ops/services/booking/stop", headers=user_headers()), 403, "FORBIDDEN")
    record = next(r for r in caplog.records if r.getMessage() == "ops audit")
    assert record.fields["userId"] == USER_SUB
    assert record.fields["action"] == "stop"
    assert record.fields["target"] == "booking"
    assert record.fields["result"] == "denied"
    assert _denied() == before + 1


def test_trace_id_kept_when_valid_and_replaced_when_not(client):
    good = "0123456789abcdef0123456789abcdef"
    r = client.get("/ops/services", headers={"X-Trace-Id": good})
    assert r.json()["traceId"] == good
    r = client.get("/ops/services", headers={"X-Trace-Id": "INJECTED-value"})
    assert r.json()["traceId"] != "INJECTED-value"
    assert TRACE.fullmatch(r.json()["traceId"])


def test_cognito_not_configured_is_503(client, app):
    app.state.verifier._issuer = ""
    body = _error(client.get("/ops/services", headers=admin_headers()), 503, "SERVICE_UNAVAILABLE")
    assert body["details"][0]["reason"] == "NOT_CONFIGURED"


def _denied() -> float:
    return (
        REGISTRY.get_sample_value("ops_action_total", {"action": "stop", "result": "denied"}) or 0.0
    )
