from __future__ import annotations

from fastapi import FastAPI

from seatsac_ai import __version__
from seatsac_ai.api import actuator, chat, presets
from seatsac_ai.common.errors import install_exception_handlers
from seatsac_ai.common.logging import configure_logging
from seatsac_ai.common.metrics import init_label_series
from seatsac_ai.common.middleware import RequestContextMiddleware
from seatsac_ai.common.readiness import ReadinessRegistry
from seatsac_ai.config import Settings, get_settings


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.app_env)
    init_label_series()

    # 문서 화면(/docs·/redoc·/openapi.json)은 끈다. 계약 원본은 contracts/openapi/ai.openapi.yaml
    app = FastAPI(
        title="SeatSAC AI", version=__version__, docs_url=None, redoc_url=None, openapi_url=None
    )
    app.state.settings = settings
    app.state.readiness = ReadinessRegistry()

    app.add_middleware(RequestContextMiddleware)
    install_exception_handlers(app)

    app.include_router(actuator.router)
    app.include_router(presets.router)
    app.include_router(chat.router)
    return app


app = create_app()
