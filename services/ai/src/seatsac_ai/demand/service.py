"""동기 MySQL 작업을 이벤트 루프 밖에서 실행하고 응답 대기·동시 작업 수를 제한한다.

응답 기한을 넘긴 스레드를 강제 종료할 수는 없다. 완료 전까지 작업 슬롯을 유지하며
재시도하지 않는다(저장은 늦게 커밋됐을 수 있음). DB 오류 문자열은 로그에 넣지 않는다.
"""

from __future__ import annotations

import asyncio
import logging
import ssl
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from threading import BoundedSemaphore
from typing import Any

import pymysql

from seatsac_ai.config import Settings
from seatsac_ai.demand.repository import MySqlDemandRepository

log = logging.getLogger(__name__)
STATS_TIMEOUT = 2.0
RECORD_TIMEOUT = 0.25
MAX_WORKERS = 5


class DemandUnavailable(Exception):
    """DB 미설정·장애·과부하·시간 초과. 비밀값이 없는 서비스 경계 오류."""

    def __init__(self, category: str = "INTERNAL") -> None:
        super().__init__()
        self.category = category


class DemandService:
    def __init__(self, repository: MySqlDemandRepository) -> None:
        self._repository = repository
        self._executor = ThreadPoolExecutor(max_workers=MAX_WORKERS, thread_name_prefix="ai-demand")
        self._slots = BoundedSemaphore(MAX_WORKERS)

    async def _run(self, fn, *args, timeout: float, **kwargs):
        # 대기열을 만들지 않는다. 시간 초과된 작업도 실제 종료 전까지 슬롯을 차지한다.
        if timeout <= 0:
            raise DemandUnavailable("TIMEOUT")
        if not self._slots.acquire(blocking=False):
            raise DemandUnavailable
        try:
            future = self._executor.submit(fn, *args, **kwargs)
        except Exception:
            self._slots.release()
            raise DemandUnavailable from None
        future.add_done_callback(lambda _: self._slots.release())
        try:
            return await asyncio.wait_for(asyncio.wrap_future(future), timeout=timeout)
        except Exception as exc:
            category = "TIMEOUT" if isinstance(exc, TimeoutError) else "INTERNAL"
            log.warning(
                "수요 DB 작업 실패(%s)", type(exc).__name__,
                extra={"fields": {"errorCategory": category}},
            )
            raise DemandUnavailable(category) from None

    async def record(self, fields: dict[str, Any], *, timeout: float = RECORD_TIMEOUT) -> None:
        """이미 정제한 요약만 저장한다. slots는 초기에는 빈 object(원문 유입 방지)."""
        try:
            now = datetime.now(UTC)
            # DATETIME(3)의 기본 반올림으로 KST 자정 직전 요청이 다음 날로 넘어가지 않게.
            created_at = now.replace(microsecond=now.microsecond // 1000 * 1000, tzinfo=None)
            await self._run(
                self._repository.insert,
                category=fields["category"], reason=fields["reason"],
                question_hash=fields["questionHash"], slots={},
                created_at=created_at,
                timeout=min(RECORD_TIMEOUT, timeout),
            )
        except DemandUnavailable as exc:
            # 부가 기록 실패 때문에 정상 OUT_OF_SCOPE 답을 503으로 바꾸지 않는다.
            log.warning(
                "수요 DB 기록을 확인하지 못함(재시도 없음)",
                extra={"fields": {"errorCategory": exc.category}},
            )

    async def stats(self, start: datetime, end: datetime, limit: int) -> dict[str, Any]:
        return await self._run(self._repository.stats, start, end, limit, timeout=STATS_TIMEOUT)

    def close(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)


def build_demand_service(settings: Settings) -> DemandService | None:
    if not settings.unmet_demand_enabled:
        return None
    if (
        not settings.db_host or not settings.db_password
        or not settings.db_password.get_secret_value()
    ):
        log.warning("수요 DB 설정이 부족해 저장·통계를 비활성화함")
        return None
    if settings.app_env == "staging" and not settings.db_ssl_ca:
        log.warning("수요 DB의 RDS CA 설정이 없어 저장·통계를 비활성화함")
        return None
    try:
        tls = ssl.create_default_context(cafile=settings.db_ssl_ca)
    except (OSError, ssl.SSLError):
        log.warning("수요 DB CA를 읽을 수 없어 저장·통계를 비활성화함")
        return None

    def connect():
        return pymysql.connect(
            host=settings.db_host, port=settings.db_port, user="ai_app", database="ai_db",
            password=settings.db_password.get_secret_value(), charset="utf8mb4",
            connect_timeout=1, read_timeout=1, write_timeout=1,
            ssl=tls, autocommit=False,
            # 6.4.4 통계용(2초 응답 한계보다 짧게). ai_query의 5초 설정은 6.4.2 범위다.
            init_command="SET SESSION time_zone = '+00:00', MAX_EXECUTION_TIME = 1500",
        )

    return DemandService(MySqlDemandRepository(connect))
