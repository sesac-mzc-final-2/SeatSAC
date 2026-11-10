from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI

from seatsac_ai import __version__
from seatsac_ai.api import actuator, chat, presets
from seatsac_ai.cache.embedder import FastEmbedder
from seatsac_ai.cache.store import ChatCache, Embedder, ValkeySearchIndex
from seatsac_ai.common.errors import install_exception_handlers
from seatsac_ai.common.logging import configure_logging
from seatsac_ai.common.metrics import init_label_series
from seatsac_ai.common.middleware import RequestContextMiddleware
from seatsac_ai.common.readiness import ReadinessRegistry
from seatsac_ai.config import Settings, get_settings
from seatsac_ai.llm.client import AnthropicClient, LlmClient
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.quota.limiter import QuotaLimiter, connect_valkey
from seatsac_ai.snapshot import Snapshot, build_snapshot

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    snapshot: Snapshot | None = app.state.snapshot
    if snapshot is not None:
        snapshot.start()  # 첫 적재가 끝날 때까지 readiness는 DOWN
    cache: ChatCache | None = app.state.cache
    warm = None
    if cache is not None:
        await cache.start()  # 의미 캐시 인덱스. 실패하면 정확 일치만(B1)
        if cache.semantic_ready and isinstance(cache.embedder, FastEmbedder):
            # 임베딩 모델을 미리 읽는다(몇 초). 첫 질문이 기다리지 않게
            warm = asyncio.create_task(asyncio.to_thread(cache.embedder.load))
    try:
        yield
    finally:
        if warm is not None:
            warm.cancel()
        if snapshot is not None:
            await snapshot.stop()
        if app.state.valkey is not None:
            await app.state.valkey.aclose()


def create_app(
    settings: Settings | None = None,
    *,
    llm: LlmClient | None = None,
    valkey: Any | None = None,
    embedder: Embedder | None = None,
    semantic_index: Any | None = None,
) -> FastAPI:
    """llm·valkey·embedder·semantic_index 는 테스트가 가짜를 넣을 때만 준다.

    없으면 설정으로 만든다.
    """
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

    # 사용 한도(3.4.3) — ai Valkey. readiness 조건이기도 하다(계약 9장 "ai Valkey PING")
    if valkey is None and settings.ai_valkey_host:
        valkey = connect_valkey(
            settings.ai_valkey_host, settings.ai_valkey_port, settings.ai_valkey_tls
        )
    app.state.valkey = valkey
    app.state.limiter = None
    if valkey is not None:
        app.state.limiter = QuotaLimiter(valkey, settings.limit_per_minute, settings.limit_per_day)
        app.state.readiness.register("valkey", valkey.ping)
    elif settings.app_env == "staging":
        log.error("AI_VALKEY_HOST가 없다 — 질문을 받지 않고 readiness DOWN으로 둔다")
        app.state.readiness.register("valkey", lambda: False)
    else:
        log.warning("AI_VALKEY_HOST가 없어 사용 한도 없이 동작한다(local)")

    # 캐시·세션(3.4.5) — 사용 한도와 같은 ai Valkey. 없으면(local) 늘 Claude로 답한다
    app.state.cache = None
    if valkey is not None:
        if settings.semantic_cache_enabled:
            embedder = embedder or FastEmbedder()
            semantic_index = semantic_index or ValkeySearchIndex(valkey)
        app.state.cache = ChatCache(
            valkey,
            ttl=settings.cache_ttl,
            session_ttl=settings.session_ttl,
            threshold=settings.semantic_threshold,
            embedder=embedder if settings.semantic_cache_enabled else None,
            index=semantic_index if settings.semantic_cache_enabled else None,
        )

    # 질문 처리(3.4.2·3.4.3). 스냅샷이 없으면 /ai/chat 은 503 AI_SNAPSHOT_NOT_READY
    app.state.chat = None
    if app.state.snapshot is not None:
        if llm is None:
            key = settings.anthropic_api_key
            llm = AnthropicClient(key.get_secret_value() if key else None, settings.ai_model)
        app.state.chat = ChatService(app.state.snapshot.store, llm, settings, cache=app.state.cache)

    app.add_middleware(RequestContextMiddleware)
    install_exception_handlers(app)

    app.include_router(actuator.router)
    app.include_router(presets.router)
    app.include_router(chat.router)
    return app


app = create_app()
