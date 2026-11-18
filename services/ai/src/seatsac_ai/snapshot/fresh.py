"""질문 시점 잔여석 갱신 — 잔여석을 읽는 SQL 직전에 회차를 공개 API에서 새로 받는다.

주기 갱신(오늘 1분 · 나머지 10분)만으로는 잔여석이 그만큼 늦다. 잔여석은 예매 때마다 바뀌는
유일한 칸이라, SQL이 잔여석을 읽을 때만 실행 직전에 모든 날짜(오늘~6일 뒤)의 회차를 새로 받는다
(APP_AI_SNAPSHOT_REFRESH_ON_CHAT). 영화 제목·상영 시각처럼 바뀌지 않는 칸만 묻는 질문은
기다리지 않는다.

  - 동시에 온 질문은 진행 중인 갱신 한 번을 같이 기다린다(catalog 호출이 질문 수만큼 늘지 않게)
  - 제한 시간(기본 2초) 안에 못 받거나 실패하면 기존 스냅샷으로 답한다. 질문은 실패시키지 않는다
    (답에는 기준 시각 asOf 가 붙는다). 제한 시간이 지나도 갱신 자체는 끝까지 돌아 다음 질문이 쓴다
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from typing import TYPE_CHECKING

import sqlglot
from sqlglot import exp

if TYPE_CHECKING:
    from seatsac_ai.sql.validator import ValidatedSql

log = logging.getLogger(__name__)

_SEAT_COLUMNS = frozenset({"remaining_seats", "remaining_seats_source"})


def reads_seats(query: ValidatedSql) -> bool:
    """screening 의 잔여석 칸을 읽는 SQL인가. `SELECT *`도 잔여석을 읽는 것으로 본다."""
    if "screening" not in query.tables:
        return False
    tree = sqlglot.parse_one(query.sql, read="sqlite")
    for node in tree.walk():
        if isinstance(node, exp.Star):
            return True
        if isinstance(node, exp.Column) and node.name.lower() in _SEAT_COLUMNS:
            return True
    return False


class SeatRefresher:
    def __init__(self, refresh: Callable[[], Awaitable[None]], timeout: float) -> None:
        self._refresh = refresh
        self._timeout = timeout
        self._running: asyncio.Task[bool] | None = None

    async def ensure_fresh(self) -> bool:
        """회차를 새로 받았으면 True, 기존 스냅샷으로 답해야 하면 False."""
        task = self._running
        if task is None or task.done():
            task = asyncio.create_task(self._run(), name="snapshot-seats-on-chat")
            self._running = task
        try:
            # shield: 이 질문이 제한 시간으로 먼저 떠나도 갱신은 취소하지 않는다
            return await asyncio.wait_for(asyncio.shield(task), self._timeout)
        except TimeoutError:
            log.warning(
                "질문 시점 갱신이 %.1f초 안에 끝나지 않아 기존 스냅샷으로 답한다", self._timeout
            )
            return False

    async def _run(self) -> bool:
        try:
            await self._refresh()
        except Exception:
            log.warning("질문 시점 갱신 실패 — 기존 스냅샷으로 답한다", exc_info=True)
            return False
        return True
