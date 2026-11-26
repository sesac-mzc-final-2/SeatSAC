"""Prometheus 읽기 — 정해 둔 PromQL만(사용자 입력을 쿼리에 넣지 않는다, ADR-119).

실패하면 빈 결과를 준다 — 상태 화면은 지표 칸을 null로 보이고 계속 동작한다(ops.openapi.yaml
listServices).
"""

from __future__ import annotations

import logging
import math

import httpx

from seatsac_ops.config import Settings

log = logging.getLogger(__name__)

# 신호등 (gitops observability/prometheusrule.yaml, 런타임 계약 5장): 0 정상 · 1 주의 · 2 장애
SERVICE_STATE = "cinema:service_state"
# 노드 CPU·메모리 — kubelet(cAdvisor) 루트 cgroup 사용량 ÷ kube-state-metrics 할당 가능량. 둘 다
# `node` 라벨
NODE_CPU = (
    'round(100 * sum by (node) (rate(container_cpu_usage_seconds_total{id="/"}[5m]))'
    ' / sum by (node) (kube_node_status_allocatable{resource="cpu"}))'
)
NODE_MEMORY = (
    'round(100 * sum by (node) (container_memory_working_set_bytes{id="/"})'
    ' / sum by (node) (kube_node_status_allocatable{resource="memory"}))'
)


class PromClient:
    def __init__(
        self, settings: Settings, transport: httpx.AsyncBaseTransport | None = None
    ) -> None:
        self._enabled = settings.prometheus_url is not None
        self._http = httpx.AsyncClient(
            base_url=settings.prometheus_url or "http://prometheus.invalid",
            timeout=settings.prometheus_timeout_seconds,
            transport=transport,
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    async def vector(self, query: str, label: str) -> dict[str, float]:
        """instant query → {라벨 값: 값}. 실패·빈 결과는 {}."""
        if not self._enabled:
            return {}
        try:
            response = await self._http.get("/api/v1/query", params={"query": query})
            response.raise_for_status()
            body = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            log.warning("Prometheus 조회 실패 — 지표 칸을 비운다: %s", type(exc).__name__)
            return {}
        out: dict[str, float] = {}
        for item in (body.get("data") or {}).get("result") or []:
            key = (item.get("metric") or {}).get(label)
            try:
                value = float(item["value"][1])
            except (KeyError, IndexError, TypeError, ValueError):
                continue
            if key is not None and math.isfinite(value):
                out[key] = value
        return out
