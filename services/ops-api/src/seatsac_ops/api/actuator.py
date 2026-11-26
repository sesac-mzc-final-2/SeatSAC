"""헬스·지표 — Java 서비스·ai와 같은 경로라 프로브·PodMonitor 템플릿을 그대로 쓴다.

readiness는 외부 의존을 보지 않는다(gateway와 같은 readinessState만): Kubernetes
API·Prometheus·Athena 장애는
각 API가 503·null로 알린다. ALB 대상 그룹 헬스 체크도 이 경로다.
"""

from __future__ import annotations

from fastapi import APIRouter, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

router = APIRouter(include_in_schema=False)


@router.get("/actuator/health/liveness")
async def liveness() -> dict[str, str]:
    return {"status": "UP"}


@router.get("/actuator/health/readiness")
async def readiness() -> dict[str, str]:
    return {"status": "UP"}


@router.get("/actuator/prometheus")
def prometheus() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
