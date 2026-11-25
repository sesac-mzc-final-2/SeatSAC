"""OPS-20 서비스·노드 상태 · OPS-21 정지/가동 (ops.openapi.yaml, ADR-119).

- 정지: 정지 전 replicas를 ConfigMap ops-api-state에 먼저 적고 → scale 0. 이미 0이면 그대로
  202(멱등)
- 가동: 적어 둔 값 → 없으면 Git 값(ArgoCD가 남긴 last-applied-configuration) → HPA 최소 → 1. 이미
  가동 중이면 그대로
- booking·gateway(Rollout)는 블루/그린 진행 중(currentPodHash ≠ stableRS, 또는 일시 정지)이면 409 —
  전환 도중
  replicas를 바꾸면 preview·active 수가 어긋난다
- Git과 다른 replicas는 의도된 GitOps 예외(서비스 Application ignoreDifferences /spec/replicas)
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from seatsac_ops.common.errors import not_stoppable, rollout_in_progress, upstream_unavailable
from seatsac_ops.kube import DEPLOYMENT, ROLLOUT, KubeClient, KubeError
from seatsac_ops.prom import NODE_CPU, NODE_MEMORY, SERVICE_STATE, PromClient

log = logging.getLogger(__name__)
KST = ZoneInfo("Asia/Seoul")

# 화면 순서 = 이 순서. kind는 gitops base/{svc}의 리소스 종류(블루/그린은 booking·gateway만, 런타임
# 계약 2장)
SERVICES: dict[str, str] = {
    "gateway": ROLLOUT,
    "booking": ROLLOUT,
    "catalog": DEPLOYMENT,
    "user": DEPLOYMENT,
    "payment": DEPLOYMENT,
    "coupon": DEPLOYMENT,
    "ai": DEPLOYMENT,
    "ops-api": DEPLOYMENT,
}
# ops-api 자신은 정지 대상이 아니다(멈추면 다시 켤 수단이 없다)
STOPPABLE = frozenset(SERVICES) - {"ops-api"}

LAST_APPLIED = "kubectl.kubernetes.io/last-applied-configuration"


def _int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _desired(workload: dict[str, Any]) -> int:
    spec = workload.get("spec") or {}
    return _int(spec.get("replicas"), 1) if "replicas" in spec else 1


def rollout_busy(workload: dict[str, Any]) -> bool:
    status = workload.get("status") or {}
    current, stable = status.get("currentPodHash"), status.get("stableRS")
    if current and stable and current != stable:
        return True
    return bool(status.get("pauseConditions"))


def git_replicas(workload: dict[str, Any]) -> int | None:
    """ArgoCD(client-side apply)가 남긴 마지막 적용 값의 spec.replicas — Git 값."""
    raw = ((workload.get("metadata") or {}).get("annotations") or {}).get(LAST_APPLIED)
    if not raw:
        return None
    try:
        value = (json.loads(raw).get("spec") or {}).get("replicas")
    except (ValueError, AttributeError):
        return None
    return value if isinstance(value, int) and value > 0 else None


class WorkloadService:
    def __init__(self, kube: KubeClient, prom: PromClient) -> None:
        self._kube = kube
        self._prom = prom

    # --- OPS-20 -------------------------------------------------------------
    async def status(self) -> dict[str, Any]:
        try:
            workloads, hpas, state = await asyncio.gather(
                asyncio.gather(*(self._kube.get_workload(k, s) for s, k in SERVICES.items())),
                asyncio.gather(*(self._kube.get_hpa(s) for s in SERVICES)),
                self._state_or_empty(),
            )
        except KubeError as exc:
            raise upstream_unavailable("kubernetes", exc.reason) from exc
        signals, cpu, memory, nodes = await asyncio.gather(
            self._prom.vector(SERVICE_STATE, "application"),
            self._prom.vector(NODE_CPU, "node"),
            self._prom.vector(NODE_MEMORY, "node"),
            self._nodes_or_empty(),
        )

        items = []
        for (service, kind), workload, hpa in zip(SERVICES.items(), workloads, hpas, strict=True):
            if workload is None:
                continue  # 아직 배포되지 않은 서비스(예: coupon 6.1.5 전)
            desired = _desired(workload)
            state_value = signals.get(service)
            items.append(
                {
                    "service": service,
                    "kind": kind,
                    "desired": desired,
                    "ready": _int((workload.get("status") or {}).get("readyReplicas")),
                    "state": int(state_value) if state_value in (0, 1, 2) else None,
                    "hpa": _hpa_status(hpa),
                    "stopped": desired == 0 and service in state,
                }
            )
        return {
            "items": items,
            "nodes": [_node_status(n, cpu, memory) for n in nodes],
            "asOf": datetime.now(KST).isoformat(timespec="seconds"),
        }

    # --- OPS-21 -------------------------------------------------------------
    async def stop(self, service: str) -> dict[str, Any]:
        kind, workload = await self._target(service)
        current = _desired(workload)
        if current == 0:
            return {"service": service, "desired": 0}
        try:
            await self._kube.set_state(
                service, current
            )  # 먼저 적는다 — scale이 성공했는데 값을 잃지 않게
            await self._kube.scale(kind, service, 0)
        except KubeError as exc:
            raise upstream_unavailable("kubernetes", exc.reason) from exc
        return {"service": service, "desired": 0, "previous": current}

    async def start(self, service: str) -> dict[str, Any]:
        kind, workload = await self._target(service)
        current = _desired(workload)
        if current > 0:
            return {"service": service, "desired": current}
        try:
            remembered = _int((await self._state_or_empty()).get(service), 0)
            target = remembered or git_replicas(workload) or await self._hpa_min(service) or 1
            await self._kube.scale(kind, service, target)
            if remembered:
                await self._kube.set_state(service, None)
        except KubeError as exc:
            raise upstream_unavailable("kubernetes", exc.reason) from exc
        return {"service": service, "desired": target}

    # --- 내부 ---------------------------------------------------------------
    async def _target(self, service: str) -> tuple[str, dict[str, Any]]:
        if service not in STOPPABLE:
            raise not_stoppable(service)
        kind = SERVICES[service]
        try:
            workload = await self._kube.get_workload(kind, service)
        except KubeError as exc:
            raise upstream_unavailable("kubernetes", exc.reason) from exc
        if workload is None:
            raise upstream_unavailable("kubernetes", "NOT_DEPLOYED")
        if kind == ROLLOUT and rollout_busy(workload):
            raise rollout_in_progress(service)
        return kind, workload

    async def _state_or_empty(self) -> dict[str, str]:
        try:
            return await self._kube.get_state()
        except KubeError as exc:
            if exc.status == 404:
                return {}
            raise

    async def _hpa_min(self, service: str) -> int | None:
        hpa = await self._kube.get_hpa(service)
        if hpa is None:
            return None
        value = _int((hpa.get("spec") or {}).get("minReplicas"), 1)
        return value if value > 0 else None

    async def _nodes_or_empty(self) -> list[dict[str, Any]]:
        try:
            return await self._kube.list_nodes()
        except KubeError as exc:
            log.warning("노드 목록을 읽지 못했다 — nodes를 비운다: %s", exc.reason)
            return []


def _hpa_status(hpa: dict[str, Any] | None) -> dict[str, Any] | None:
    if hpa is None:
        return None
    spec, status = hpa.get("spec") or {}, hpa.get("status") or {}
    cpu = None
    for metric in status.get("currentMetrics") or []:
        resource = metric.get("resource") or {}
        if resource.get("name") == "cpu":
            value = (resource.get("current") or {}).get("averageUtilization")
            cpu = value if isinstance(value, int) else None
    return {
        "min": _int(spec.get("minReplicas"), 1),
        "max": _int(spec.get("maxReplicas")),
        "current": _int(status.get("currentReplicas")),
        "cpuPercent": cpu,
    }


def _node_status(node: dict[str, Any], cpu: dict[str, float], memory: dict[str, float]) -> dict:
    meta = node.get("metadata") or {}
    labels = meta.get("labels") or {}
    name = meta.get("name") or ""
    capacity = (
        labels.get("karpenter.sh/capacity-type")
        or labels.get("eks.amazonaws.com/capacityType")
        or ""
    )
    return {
        "name": name,
        "zone": labels.get("topology.kubernetes.io/zone") or "",
        "cpuPercent": _percent(cpu.get(name)),
        "memoryPercent": _percent(memory.get(name)),
        "capacityType": "SPOT" if capacity.lower() == "spot" else "ON_DEMAND",
    }


def _percent(value: float | None) -> int | None:
    if value is None:
        return None
    return max(0, min(100, int(round(value))))
