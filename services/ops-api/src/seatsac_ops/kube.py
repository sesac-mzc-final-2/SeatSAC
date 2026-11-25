"""Kubernetes API 클라이언트(최소) — 파드 ServiceAccount 토큰으로 cinema-staging만 읽고 scale만
바꾼다.

권한은 gitops base/ops-api/rbac.yaml(Role: deployments·rollouts·hpa·pods get/list, */scale
get/patch,
ConfigMap ops-api-state get/patch · ClusterRole: nodes get/list). 클라이언트 라이브러리 없이 httpx로
부른다 —
쓰는 경로가 다섯 가지뿐이고 테스트에서 MockTransport로 바꿔 끼우기 쉽다.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import httpx

from seatsac_ops.config import Settings


class KubeError(Exception):
    """Kubernetes API를 부르지 못함(연결·타임아웃·5xx·권한) — 부르는 쪽이 503으로 바꾼다."""

    def __init__(self, reason: str, status: int | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.status = status


ROLLOUT = "ROLLOUT"
DEPLOYMENT = "DEPLOYMENT"


class KubeClient:
    def __init__(
        self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None
    ) -> None:
        self._ns = settings.k8s_namespace
        self._token_path = Path(settings.k8s_token_path)
        self._state_cm = settings.state_configmap
        verify: Any = settings.k8s_ca_path if os.path.exists(settings.k8s_ca_path) else True
        self._http = httpx.AsyncClient(
            base_url=settings.k8s_api_url,
            verify=verify,
            timeout=settings.k8s_timeout_seconds,
            transport=transport,
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    def _headers(self) -> dict[str, str]:
        # 토큰은 projected volume이라 주기적으로 바뀐다 — 매번 읽는다
        try:
            token = self._token_path.read_text().strip()
        except OSError:
            return {}
        return {"Authorization": f"Bearer {token}"}

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any] | None:
        try:
            response = await self._http.request(method, path, headers=self._headers(), **kwargs)
        except httpx.TimeoutException as exc:
            raise KubeError("TIMEOUT") from exc
        except httpx.HTTPError as exc:
            raise KubeError("CONNECT_ERROR") from exc
        if response.status_code == 404:
            return None
        if response.status_code >= 400:
            raise KubeError(f"HTTP_{response.status_code}", response.status_code)
        return response.json()

    # --- 경로 ---------------------------------------------------------------
    def _workload_path(self, kind: str, name: str) -> str:
        if kind == ROLLOUT:
            return f"/apis/argoproj.io/v1alpha1/namespaces/{self._ns}/rollouts/{name}"
        return f"/apis/apps/v1/namespaces/{self._ns}/deployments/{name}"

    async def get_workload(self, kind: str, name: str) -> dict[str, Any] | None:
        return await self._request("GET", self._workload_path(kind, name))

    async def scale(self, kind: str, name: str, replicas: int) -> None:
        result = await self._patch(
            self._workload_path(kind, name) + "/scale", {"spec": {"replicas": replicas}}
        )
        if result is None:
            raise KubeError("NOT_FOUND", 404)

    async def get_hpa(self, name: str) -> dict[str, Any] | None:
        return await self._request(
            "GET", f"/apis/autoscaling/v2/namespaces/{self._ns}/horizontalpodautoscalers/{name}"
        )

    async def list_nodes(self) -> list[dict[str, Any]]:
        body = await self._request("GET", "/api/v1/nodes")
        return list((body or {}).get("items") or [])

    async def get_state(self) -> dict[str, str]:
        body = await self._request(
            "GET", f"/api/v1/namespaces/{self._ns}/configmaps/{self._state_cm}"
        )
        if body is None:
            raise KubeError("STATE_CONFIGMAP_MISSING", 404)
        return dict(body.get("data") or {})

    async def set_state(self, service: str, replicas: int | None) -> None:
        """정지 전 replicas 기록. None이면 지운다(merge patch에서 null = 삭제)."""
        value = None if replicas is None else str(replicas)
        result = await self._patch(
            f"/api/v1/namespaces/{self._ns}/configmaps/{self._state_cm}", {"data": {service: value}}
        )
        if result is None:
            raise KubeError("STATE_CONFIGMAP_MISSING", 404)

    async def _patch(self, path: str, body: dict[str, Any]) -> dict[str, Any] | None:
        try:
            response = await self._http.patch(
                path,
                json=body,
                headers={**self._headers(), "Content-Type": "application/merge-patch+json"},
            )
        except httpx.TimeoutException as exc:
            raise KubeError("TIMEOUT") from exc
        except httpx.HTTPError as exc:
            raise KubeError("CONNECT_ERROR") from exc
        if response.status_code == 404:
            return None
        if response.status_code >= 400:
            raise KubeError(f"HTTP_{response.status_code}", response.status_code)
        return response.json()
