from __future__ import annotations

import httpx

from tests.fakes import ADMIN_SUB, admin_headers

STATS = {
    "from": "2026-11-18",
    "to": "2026-11-24",
    "total": 3,
    "byCategory": [{"category": "recommendation", "count": 3}],
    "top": [],
}
EVENT = {
    "eventId": 3,
    "title": "VIP 선착순 50% 할인",
    "discountRate": 50,
    "startsAt": "2026-11-27T14:00:00+09:00",
    "endsAt": "2026-11-27T15:00:00+09:00",
    "totalQuantity": 100,
    "issuedQuantity": 0,
    "remainingQuantity": 100,
    "eligibleGrade": "VIP",
    "state": "UPCOMING",
    "createdAt": "2026-11-27T13:40:00+09:00",
}


def test_unmet_demand_passes_through_200_and_400(client, upstream):
    key = ("ai", "/internal/ai/unmet-demand/stats")
    upstream.responses[key] = httpx.Response(200, json=STATS)
    r = client.get(
        "/ops/ai/unmet-demand",
        params={"from": "2026-11-18", "limit": "5", "x": "1"},
        headers={**admin_headers(), "X-Trace-Id": "0123456789abcdef0123456789abcdef"},
    )
    assert r.status_code == 200 and r.json() == STATS
    sent = upstream.requests[-1]
    assert dict(sent.url.params) == {"from": "2026-11-18", "limit": "5"}  # 아는 매개변수만
    assert sent.headers["X-Trace-Id"] == "0123456789abcdef0123456789abcdef"

    error = {
        "code": "VALIDATION_FAILED",
        "message": "x",
        "traceId": "0" * 31 + "1",
        "details": [{"field": "limit", "reason": "RANGE"}],
    }
    upstream.responses[key] = httpx.Response(400, json=error)
    r = client.get("/ops/ai/unmet-demand", params={"limit": "999"}, headers=admin_headers())
    assert r.status_code == 400 and r.json() == error


def test_unmet_demand_upstream_failures_are_503(client, upstream):
    key = ("ai", "/internal/ai/unmet-demand/stats")
    for item, reason in (
        (httpx.Response(503, json={}), "HTTP_503"),
        (httpx.ReadTimeout("slow"), "TIMEOUT"),
        (httpx.ConnectError("refused"), "CONNECT_ERROR"),
    ):
        upstream.responses[key] = item
        r = client.get("/ops/ai/unmet-demand", headers=admin_headers())
        assert r.status_code == 503
        assert r.json()["details"][0] == {"field": "upstream", "value": "ai", "reason": reason}


def test_coupon_event_create_and_list(client, upstream, caplog):
    caplog.set_level("INFO", logger="seatsac_ops.audit")
    upstream.responses[("coupon", "/internal/coupon-events")] = httpx.Response(201, json=EVENT)
    body = {
        "title": "VIP 선착순 50% 할인",
        "discountRate": 50,
        "startsAt": "2026-11-27T14:00:00+09:00",
        "totalQuantity": 100,
    }
    r = client.post("/ops/coupons/events", json=body, headers=admin_headers())
    assert r.status_code == 201 and r.json() == EVENT
    sent = upstream.requests[-1]
    assert sent.headers["X-User-Id"] == ADMIN_SUB
    assert httpx.Request("POST", "/", json=body).read() == sent.content
    audit = [x for x in caplog.records if x.getMessage() == "ops audit"][-1]
    assert audit.fields["action"] == "coupon_event_create" and audit.fields["target"] == "3"

    upstream.responses[("coupon", "/internal/coupon-events")] = httpx.Response(
        200, json={"items": [EVENT]}
    )
    r = client.get("/ops/coupons/events", params={"state": "OPEN"}, headers=admin_headers())
    assert r.status_code == 200 and r.json()["items"][0]["eventId"] == 3
    assert dict(upstream.requests[-1].url.params) == {"state": "OPEN"}


def test_coupon_validation_passthrough_and_malformed_body(client, upstream):
    error = {
        "code": "VALIDATION_FAILED",
        "message": "x",
        "traceId": "a" * 32,
        "details": [{"field": "startsAt", "reason": "EVENT_OVERLAP"}],
    }
    upstream.responses[("coupon", "/internal/coupon-events")] = httpx.Response(400, json=error)
    r = client.post("/ops/coupons/events", json={"title": "t"}, headers=admin_headers())
    assert r.status_code == 400 and r.json() == error
    r = client.post(
        "/ops/coupons/events",
        content=b"{not json",
        headers={**admin_headers(), "Content-Type": "application/json"},
    )
    assert r.status_code == 400 and r.json()["details"][0]["reason"] == "MALFORMED_BODY"
    r = client.post("/ops/coupons/events", json=[1, 2], headers=admin_headers())
    assert r.status_code == 400


def test_coupon_unavailable_is_503(client, upstream):
    upstream.responses[("coupon", "/internal/coupon-events")] = httpx.ConnectError("refused")
    r = client.post("/ops/coupons/events", json={"title": "t"}, headers=admin_headers())
    assert r.status_code == 503 and r.json()["details"][0]["value"] == "coupon"
