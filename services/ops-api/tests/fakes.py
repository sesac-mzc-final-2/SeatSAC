"""테스트용 가짜.

외부 호출(Cognito JWKS·Kubernetes API·Prometheus·ai·coupon·AWS)을 모두 바꿔 끼운다.
키·AWS 권한 없음.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Any

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

ISSUER = "https://cognito-idp.ap-northeast-2.amazonaws.com/ap-northeast-2_TEST"
CLIENT_ID = "test-client"
ADMIN_SUB = "11111111-1111-4111-8111-111111111111"
USER_SUB = "22222222-2222-4222-8222-222222222222"

_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


@dataclass
class _SigningKey:
    key: Any


class FakeJwkClient:
    def __init__(self) -> None:
        self.fail_connection = False

    def get_signing_key_from_jwt(self, token: str) -> _SigningKey:
        if self.fail_connection:
            raise jwt.PyJWKClientConnectionError("down")
        header = jwt.get_unverified_header(token)
        if header.get("kid") != "test":
            raise jwt.PyJWKClientError("unknown kid")
        return _SigningKey(_KEY.public_key())


def token(
    sub: str = ADMIN_SUB,
    groups: list[str] | None = None,
    *,
    token_use: str = "access",
    client_id: str = CLIENT_ID,
    issuer: str = ISSUER,
    exp_in: int = 600,
    kid: str = "test",
) -> str:
    now = int(time.time())
    claims: dict[str, Any] = {
        "sub": sub,
        "iss": issuer,
        "token_use": token_use,
        "client_id": client_id,
        "iat": now,
        "exp": now + exp_in,
        "cognito:groups": groups if groups is not None else ["ADMIN"],
    }
    return jwt.encode(claims, _KEY, algorithm="RS256", headers={"kid": kid})


def admin_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {token()}"}


def user_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {token(USER_SUB, groups=['VIP'])}"}


# --- Kubernetes API ------------------------------------------------------------

NS = "cinema-staging"


def deployment(name: str, replicas: int, ready: int | None = None, git: int | None = None) -> dict:
    meta: dict[str, Any] = {"name": name, "annotations": {}}
    if git is not None:
        meta["annotations"]["kubectl.kubernetes.io/last-applied-configuration"] = json.dumps(
            {"spec": {"replicas": git}}
        )
    return {
        "metadata": meta,
        "spec": {"replicas": replicas},
        "status": {"readyReplicas": replicas if ready is None else ready},
    }


def rollout(name: str, replicas: int, busy: bool = False) -> dict:
    status = {
        "readyReplicas": replicas,
        "currentPodHash": "abc",
        "stableRS": "xyz" if busy else "abc",
    }
    return {"metadata": {"name": name}, "spec": {"replicas": replicas}, "status": status}


def hpa(name: str, minimum: int, maximum: int, current: int, cpu: int | None) -> dict:
    metrics = (
        []
        if cpu is None
        else [
            {
                "type": "Resource",
                "resource": {"name": "cpu", "current": {"averageUtilization": cpu}},
            }
        ]
    )
    return {
        "spec": {"minReplicas": minimum, "maxReplicas": maximum},
        "status": {"currentReplicas": current, "currentMetrics": metrics},
    }


@dataclass
class FakeKube:
    deployments: dict[str, dict] = field(default_factory=dict)
    rollouts: dict[str, dict] = field(default_factory=dict)
    hpas: dict[str, dict] = field(default_factory=dict)
    state: dict[str, str] | None = field(default_factory=dict)  # None = ConfigMap 없음
    nodes: list[dict] = field(default_factory=list)
    fail: bool = False
    calls: list[tuple[str, str, Any]] = field(default_factory=list)

    def handler(self, request: httpx.Request) -> httpx.Response:
        path, method = request.url.path, request.method
        body = json.loads(request.content) if request.content else None
        self.calls.append((method, path, body))
        if self.fail:
            return httpx.Response(500, json={})
        parts = path.strip("/").split("/")
        if path == "/api/v1/nodes":
            return httpx.Response(200, json={"items": self.nodes})
        if path.startswith(f"/api/v1/namespaces/{NS}/configmaps/ops-api-state"):
            if self.state is None:
                return httpx.Response(404, json={})
            if method == "PATCH":
                for key, value in (body or {}).get("data", {}).items():
                    if value is None:
                        self.state.pop(key, None)
                    else:
                        self.state[key] = value
            return httpx.Response(200, json={"data": dict(self.state)})
        if "horizontalpodautoscalers" in parts:
            item = self.hpas.get(parts[-1])
            return httpx.Response(200, json=item) if item else httpx.Response(404, json={})
        store = self.rollouts if "rollouts" in parts else self.deployments
        is_scale = parts[-1] == "scale"
        name = parts[-2] if is_scale else parts[-1]
        item = store.get(name)
        if item is None:
            return httpx.Response(404, json={})
        if method == "PATCH" and is_scale:
            item["spec"]["replicas"] = body["spec"]["replicas"]
            return httpx.Response(200, json={"spec": {"replicas": item["spec"]["replicas"]}})
        return httpx.Response(200, json=item)

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handler)


# --- Prometheus -----------------------------------------------------------------


@dataclass
class FakeProm:
    series: dict[str, list[tuple[dict, float]]] = field(default_factory=dict)
    fail: bool = False
    queries: list[str] = field(default_factory=list)

    def handler(self, request: httpx.Request) -> httpx.Response:
        query = request.url.params.get("query", "")
        self.queries.append(query)
        if self.fail:
            return httpx.Response(503, json={})
        result = [
            {"metric": metric, "value": [0, str(value)]}
            for key, rows in self.series.items()
            if key in query
            for metric, value in rows
        ]
        return httpx.Response(200, json={"status": "success", "data": {"result": result}})

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handler)


# --- ai·coupon 내부 API -----------------------------------------------------------


@dataclass
class FakeUpstream:
    responses: dict[tuple[str, str], httpx.Response | Exception] = field(default_factory=dict)
    requests: list[httpx.Request] = field(default_factory=list)

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        item = self.responses.get((request.url.host, request.url.path))
        if item is None:
            return httpx.Response(500, json={})
        if isinstance(item, Exception):
            raise item
        return item

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handler)
