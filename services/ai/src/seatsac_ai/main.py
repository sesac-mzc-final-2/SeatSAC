from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from seatsac_ai import __version__
from seatsac_ai.api import actuator, chat, presets
from seatsac_ai.common.errors import install_exception_handlers
from seatsac_ai.common.logging import configure_logging
from seatsac_ai.common.metrics import init_label_series
from seatsac_ai.common.middleware import RequestContextMiddleware
from seatsac_ai.common.readiness import ReadinessRegistry
from seatsac_ai.config import Settings, get_settings
from seatsac_ai.snapshot import Snapshot, build_snapshot

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    snapshot: Snapshot | None = app.state.snapshot
    if snapshot is not None:
        snapshot.start()  # 첫 적재가 끝날 때까지 readiness는 DOWN
    try:
        yield
    finally:
        if snapshot is not None:
            await snapshot.stop()


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.app_env)
    init_label_series()

    # 문서 화면(/docs·/redoc·/openapi.json)은 끈다. 계약 원본은 contracts/openapi/ai.openapi.yaml
    app = FastAPI(
        title="SeatSAC AI",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.readiness = ReadinessRegistry()

    # 스냅샷(3.4.9). 공개 API 주소가 없으면 켜지 않는다(local). staging이면 readiness DOWN.
    # 켜면 첫 적재가 끝나야 readiness가 UP이 된다.
    app.state.snapshot = None
    if settings.public_api_base_url:
        snapshot = build_snapshot(settings, settings.public_api_base_url)
        app.state.snapshot = snapshot
        app.state.readiness.register("snapshot", snapshot.store.is_ready)
    elif settings.app_env == "staging":
        # staging에서는 스냅샷이 readiness 조건이다(계약 9장). 주소가 빠졌으면 조용히 UP이 되지 않게
        # 점검을 DOWN으로 등록한다. 기동은 막지 않는다 — CI 스모크는 503도 통과로 본다.
        log.error("PUBLIC_API_BASE_URL이 없다 — 스냅샷 없이 readiness DOWN으로 둔다")
        app.state.readiness.register("snapshot", lambda: False)
    else:
        log.warning("PUBLIC_API_BASE_URL이 없어 스냅샷 적재를 시작하지 않는다(local)")

    app.add_middleware(RequestContextMiddleware)
    install_exception_handlers(app)

    app.include_router(actuator.router)
    app.include_router(presets.router)
    app.include_router(chat.router)
    return app


app = create_app()
