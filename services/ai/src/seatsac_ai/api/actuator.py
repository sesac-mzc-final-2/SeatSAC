"""헬스·지표. Java 서비스와 같은 경로라 프로브·PodMonitor 템플릿을 그대로 쓴다.

gateway는 /api/actuator/** 를 404로 막으므로 이 경로는 클러스터 안(kubelet·Prometheus)에서만 닿는다.
"""

from __future__ import annotations

from fastapi import APIRouter, Request, Response
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

router = APIRouter(include_in_schema=False)


@router.get("/actuator/health/liveness")
async def liveness() -> dict[str, str]:
    """프로세스만 본다. 외부 의존성은 보지 않는다(Valkey가 죽었다고 파드를 재시작하지 않게)."""
    return {"status": "UP"}


@router.get("/actuator/health/readiness")
async def readiness(request: Request) -> JSONResponse:
    ready, components = await request.app.state.readiness.evaluate()
    return JSONResponse(
        {"status": "UP" if ready else "OUT_OF_SERVICE", "components": components},
        status_code=200 if ready else 503,
    )


@router.get("/actuator/prometheus")
def prometheus() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
