"""사용 한도 (WBS 3.4.3 · 기능명세서 v4 15-1 규칙 · conventions 4장 ai Valkey 키).

사용자당 동시 1건 · 분당 10회 · 하루 100회. Claude 비용을 지키는 장치라서
**한도를 확인할 수 없으면(ai Valkey 장애) 질문을 받지 않는다** — 503 AI_LLM_UNAVAILABLE
(reason QUOTA_STORE_UNAVAILABLE). 확인 없이 Claude를 부르지 않는다.

키
  ai:inflight:{userId}                   40초   동시 1건 (값 = 이 요청의 토큰)
  ai:quota:min:{userId}:{yyyyMMddHHmm}   60초   분당 카운터
  ai:quota:day:{userId}:{yyyyMMdd}       1일    하루 카운터 (KST 날짜)
"""

from __future__ import annotations

import logging
import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from typing import Any

from redis.exceptions import RedisError

from seatsac_ai.common.errors import llm_unavailable, query_in_progress, quota_exceeded
from seatsac_ai.common.metrics import AI_QUOTA_REJECTED
from seatsac_ai.snapshot.mapper import KST

log = logging.getLogger(__name__)

INFLIGHT_TTL_MS = 40_000  # 요청 기한 25초 · gateway 28초보다 길게(처리 중 풀리지 않게)

# 처음 증가할 때만 TTL을 단다 — 한 번에 원자적으로(INCR 뒤 죽어서 TTL 없는 키가 남지 않게)
_INCR_WITH_TTL = """
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return count
"""
# 값이 내 토큰일 때만 지운다 — 늦게 끝난 요청이 다음 요청의 잠금을 지우지 않게
_RELEASE_IF_MINE = """
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
return 0
"""


class QuotaLimiter:
    def __init__(self, valkey: Any, per_minute: int, per_day: int) -> None:
        self._valkey = valkey
        self._per_minute = per_minute
        self._per_day = per_day

    @asynccontextmanager
    async def acquire(self, user_id: str, now: datetime | None = None) -> AsyncIterator[None]:
        now = (now or datetime.now(KST)).astimezone(KST)
        inflight_key = f"ai:inflight:{user_id}"
        token = secrets.token_hex(16)
        try:
            if not await self._valkey.set(inflight_key, token, nx=True, px=INFLIGHT_TTL_MS):
                raise query_in_progress()
            try:
                await self._count(user_id, now)
            except BaseException:
                await self._release(inflight_key, token)
                raise
        except RedisError as exc:
            log.error("ai Valkey에 한도를 기록하지 못했다: %s", exc)
            raise llm_unavailable("QUOTA_STORE_UNAVAILABLE") from exc
        try:
            yield
        finally:
            await self._release(inflight_key, token)

    async def _count(self, user_id: str, now: datetime) -> None:
        minute = await self._valkey.eval(
            _INCR_WITH_TTL, 1, f"ai:quota:min:{user_id}:{now:%Y%m%d%H%M}", 60
        )
        if int(minute) > self._per_minute:
            AI_QUOTA_REJECTED.labels("minute").inc()
            raise quota_exceeded("PER_MINUTE", self._per_minute, 60 - now.second)
        day = await self._valkey.eval(
            _INCR_WITH_TTL, 1, f"ai:quota:day:{user_id}:{now:%Y%m%d}", 86_400
        )
        if int(day) > self._per_day:
            AI_QUOTA_REJECTED.labels("day").inc()
            midnight = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
            raise quota_exceeded("PER_DAY", self._per_day, int((midnight - now).total_seconds()))

    async def _release(self, key: str, token: str) -> None:
        try:
            await self._valkey.eval(_RELEASE_IF_MINE, 1, key, token)
        except RedisError:
            log.warning("동시 1건 잠금을 풀지 못했다 — 40초 뒤 TTL로 풀린다")


def connect_valkey(host: str, port: int, tls: bool) -> Any:
    """ai Valkey 연결(지연 연결 — 만들 때는 접속하지 않는다). 응답 상한 1초."""
    from redis.asyncio import Redis

    return Redis(
        host=host,
        port=port,
        ssl=tls,
        decode_responses=True,
        socket_timeout=1.0,
        socket_connect_timeout=1.0,
    )
