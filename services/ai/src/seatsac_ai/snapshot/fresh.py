"""질문 시점 잔여석 갱신 — 잔여석을 읽는 SQL 직전에, 그 SQL이 조회하는 날짜 × 극장만 새로 받는다.

주기 갱신(오늘 1분 · 나머지 10분)만으로는 잔여석이 그만큼 늦다. 잔여석은 예매 때마다 바뀌는
유일한 칸이라, 잔여석을 묻는 질문에서만 실행 직전에 공개 API(catalog 회차 조회)를 다시 부른다.
범위는 SQL 조건 분석기(sql/conditions.py)가 읽은 날짜·극장이다. 「오늘 건대입구」면 호출 1번.

  - 같은 범위를 동시에 묻는 질문은 진행 중인 갱신 한 번을 같이 기다린다
  - 받아 온 회차에 확인 불가(TOTAL — booking이 늦게 답함)가 있으면 한 번만 더 받는다
  - 제한 시간(기본 2초) 안에 못 받거나 실패하면 기존 스냅샷으로 답한다(질문은 실패시키지 않는다).
    제한 시간이 지나도 갱신 자체는 끝까지 돌아 다음 질문이 쓴다
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any

log = logging.getLogger(__name__)

Refresh = Callable[[Sequence[date] | None, Sequence[int] | None], Awaitable[Any]]
_Key = tuple[frozenset[date] | None, frozenset[int] | None]


@dataclass(frozen=True)
class Fresh:
    """ok=False 면 기존 스냅샷으로 답한다. at = 새로 받은 시각, unknown = 확인 불가 회차 수."""

    ok: bool
    at: datetime | None = None
    unknown: int = 0


class SeatRefresher:
    def __init__(self, refresh: Refresh, timeout: float) -> None:
        self._refresh = refresh
        self._timeout = timeout
        self._running: dict[_Key, asyncio.Task[Fresh]] = {}

    async def ensure_fresh(
        self, days: Sequence[date] | None = None, theater_ids: Sequence[int] | None = None
    ) -> Fresh:
        """days·theater_ids 가 None 이면 그 축 전체."""
        key: _Key = (
            frozenset(days) if days is not None else None,
            frozenset(theater_ids) if theater_ids is not None else None,
        )
        task = self._running.get(key)
        if task is None or task.done():
            task = asyncio.create_task(self._run(days, theater_ids), name="snapshot-seats")
            self._running[key] = task
            task.add_done_callback(lambda t, k=key: self._forget(k, t))
        try:
            # shield: 이 질문이 제한 시간으로 먼저 떠나도 갱신은 취소하지 않는다
            return await asyncio.wait_for(asyncio.shield(task), self._timeout)
        except TimeoutError:
            log.warning(
                "질문 시점 갱신이 %.1f초 안에 끝나지 않아 기존 스냅샷으로 답한다", self._timeout
            )
            return Fresh(ok=False)

    def _forget(self, key: _Key, task: asyncio.Task[Fresh]) -> None:
        if self._running.get(key) is task:
            del self._running[key]

    async def _run(self, days, theater_ids) -> Fresh:
        try:
            result = await self._refresh(days, theater_ids)
            if result.unknown:
                # booking이 잠깐 늦은 경우가 많다(catalog는 0.3초만 기다린다) — 한 번만 더
                log.info("확인 불가 잔여석 %d건 — 한 번 더 받는다", result.unknown)
                result = await self._refresh(days, theater_ids)
        except Exception:
            log.warning("질문 시점 갱신 실패 — 기존 스냅샷으로 답한다", exc_info=True)
            return Fresh(ok=False)
        return Fresh(ok=True, at=result.at, unknown=result.unknown)
