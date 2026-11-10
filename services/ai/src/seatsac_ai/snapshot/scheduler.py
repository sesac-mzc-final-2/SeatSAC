"""주기 실행 — 오늘 회차 1분, 나머지 10분 (APP_AI_SNAPSHOT_TODAY_INTERVAL / _OTHER_INTERVAL).

파드 1개(워커 1) 안의 asyncio 백그라운드 작업이다. replicas 1 고정이라 별도 워커·큐는 쓰지 않는다.

  - 범위마다 자기 반복문 하나: 이전 실행이 끝나야 다음 대기가 시작되므로 같은 범위끼리 겹치지 않는다
  - 한 번 실패해도 반복문은 계속 돈다(로그만 남김). 이전 스냅샷이 유지되고
    ai_snapshot_age_seconds가 늘어난다
  - 아직 한 번도 성공하지 못한 동안에는 짧게(최대 15초) 다시 시도해서
    기동 직후의 일시 장애를 빨리 넘긴다
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable

log = logging.getLogger(__name__)

_FIRST_LOAD_RETRY_SECONDS = 15


class SnapshotScheduler:
    def __init__(
        self,
        today: Callable[[], Awaitable[None]],
        other: Callable[[], Awaitable[None]],
        today_interval: int,
        other_interval: int,
    ) -> None:
        self._jobs = (("today", today, today_interval), ("other", other, other_interval))
        self._tasks: list[asyncio.Task[None]] = []

    def start(self) -> None:
        if self._tasks:
            return
        self._tasks = [
            asyncio.create_task(self._run(name, job, interval), name=f"snapshot-{name}")
            for name, job, interval in self._jobs
        ]

    async def stop(self) -> None:
        tasks, self._tasks = self._tasks, []
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    @staticmethod
    async def _run(name: str, job: Callable[[], Awaitable[None]], interval: int) -> None:
        loaded_once = False
        while True:
            try:
                await job()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("snapshot %s 갱신 실패(이전 스냅샷 유지)", name)
                delay = interval if loaded_once else min(interval, _FIRST_LOAD_RETRY_SECONDS)
            else:
                loaded_once = True
                delay = interval
            await asyncio.sleep(delay)
