"""ops-api 진입점 (6.1.7, ADR-119). `uvicorn seatsac_ops.main:app`.

외부 주소는 CloudFront `/ops/*` → ALB(gateway Ingress의 `/ops` 규칙) → `ops-api:8080` — gateway를
거치지 않는다.
gateway를 정지해도 관리자 화면이 동작해야 해서다.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from seatsac_ops.api import actuator, ops
from seatsac_ops.auth import TokenVerifier
from seatsac_ops.common.errors import install_exception_handlers
from seatsac_ops.common.logging import configure_logging
from seatsac_ops.common.metrics import init_label_series
from seatsac_ops.common.middleware import RequestContextMiddleware
from seatsac_ops.config import Settings, get_settings
from seatsac_ops.kube import KubeClient
from seatsac_ops.prom import PromClient
from seatsac_ops.reports import ReportService
from seatsac_ops.upstream import UpstreamClient
from seatsac_ops.workloads import WorkloadService


def create_app(
    settings: Settings | None = None,
    *,
    verifier: TokenVerifier | None = None,
    kube: KubeClient | None = None,
    prom: PromClient | None = None,
    reports: ReportService | None = None,
    upstream: UpstreamClient | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.app_env)
    init_label_series()

    kube = kube or KubeClient(settings)
    prom = prom or PromClient(settings)
    upstream = upstream or UpstreamClient(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        yield
        await kube.aclose()
        await prom.aclose()
        await upstream.aclose()

    app = FastAPI(
        title="seatsac ops-api", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan
    )
    app.state.settings = settings
    app.state.verifier = verifier or TokenVerifier(settings)
    app.state.workloads = WorkloadService(kube, prom)
    app.state.reports = reports or ReportService(settings)
    app.state.upstream = upstream

    app.add_middleware(RequestContextMiddleware)
    install_exception_handlers(app)
    app.include_router(actuator.router)
    app.include_router(ops.router)
    return app


app = create_app()
