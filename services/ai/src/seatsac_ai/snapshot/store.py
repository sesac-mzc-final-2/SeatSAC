"""메모리 SQLite 보관소 — 적재 → 잠금 → 교체 (docs/service-runtime-contract.md 9-2).

`:memory:`를 `mode=ro`로 열면 비어 있는 읽기 전용 DB가 되어 적재할 수 없다. 그래서 이렇게 한다.

  1. 갱신마다 **새** `:memory:` 연결을 쓰기 가능으로 열어, 현재 내용을 복사한 뒤
     바뀐 부분만 적재한다
  2. 끝나면 그 연결을 잠근다: `PRAGMA query_only=ON` + `set_authorizer`
     (SELECT·READ·FUNCTION만 허용).
     `query_only`만으로는 SQL로 다시 끌 수 있어서(PRAGMA query_only=OFF) authorizer가 함께 필요하다
  3. 질의용 참조를 새 연결로 바꾸고, 이전 연결은 진행 중인 질의가 모두 끝나면 닫는다

질의 쪽은 잠긴 연결만 받으므로 쓰기 가능한 연결을 손에 넣을 방법이 없다.
갱신이 실패하면(예외) 이전 연결을 그대로 쓴다.

참고(3.4.2 질의 실행기): 같은 세대의 연결을 여러 질의가 함께 쓸 수 있다.
progress handler(5초 중단)를 이 연결에 거는 방식은 동시 질의를 고려해서 만들어야 한다.
"""

from __future__ import annotations

import sqlite3
import threading
from collections.abc import Callable, Iterator, Sequence
from contextlib import contextmanager
from datetime import datetime
from typing import Any

from seatsac_ai.common.metrics import SNAPSHOT_SCOPES as SCOPES
from seatsac_ai.snapshot import schema
from seatsac_ai.snapshot.mapper import KST, format_minute

_ALLOWED_ACTIONS = frozenset({sqlite3.SQLITE_SELECT, sqlite3.SQLITE_READ, sqlite3.SQLITE_FUNCTION})


class SnapshotNotReady(RuntimeError):
    """첫 적재가 끝나기 전에 조회했다. (호출 쪽이 503 AI_SNAPSHOT_NOT_READY로 바꾼다)"""


def _authorizer(action: int, *_args: Any) -> int:
    return sqlite3.SQLITE_OK if action in _ALLOWED_ACTIONS else sqlite3.SQLITE_DENY


# 문자열·BLOB 하나의 최대 크기. printf('%.*c', 300000000, 'x') 같은 SQL 한 줄이
# SQLite 명령 한 단계 안에서 수백 MB를 만들면 5초 중단(progress handler)이 끼어들 틈이 없어
# 파드가 OOM으로 죽는다(#91 리뷰).
# 스냅샷 값은 짧고 결과는 500행이라 1MB면 충분하다.
MAX_VALUE_BYTES = 1_000_000


def lock_connection(conn: sqlite3.Connection) -> None:
    """연결을 읽기 전용으로 잠근다. 순서가 중요하다: authorizer가 PRAGMA를 막기 전에 먼저 켠다."""
    conn.commit()
    conn.execute("PRAGMA query_only = ON")
    conn.set_authorizer(_authorizer)
    # SQL이 아니라 연결 설정이라 authorizer·query_only와 상관없이 걸리고, SQL로 되돌릴 수 없다
    conn.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, MAX_VALUE_BYTES)


class _Generation:
    """잠긴 연결 하나(세대). 진행 중 질의 수를 세어, 교체된 뒤 마지막 질의가 끝나면 닫는다."""

    __slots__ = ("conn", "inflight", "retired")

    def __init__(self, conn: sqlite3.Connection) -> None:
        self.conn = conn
        self.inflight = 0
        self.retired = False


class SnapshotStore:
    def __init__(self, clock: Callable[[], datetime] | None = None) -> None:
        self._clock = clock or (lambda: datetime.now(KST))
        self._started_at = self._clock()
        self._current: _Generation | None = None
        self._refreshed: dict[str, datetime] = {}
        self._write_lock = threading.Lock()  # 갱신은 한 번에 하나
        self._state_lock = threading.Lock()  # 현재 세대·질의 수 보호

    # --- 상태 -----------------------------------------------------------------
    def is_ready(self) -> bool:
        """두 범위(오늘·나머지)가 한 번씩 적재를 마쳤다. readiness 점검이 이 값을 쓴다."""
        return all(scope in self._refreshed for scope in SCOPES)

    def refreshed_at(self, scope: str) -> datetime | None:
        return self._refreshed.get(scope)

    def as_of(self) -> datetime | None:
        """응답의 기준 시각(asOf). 가장 오래된 범위 기준으로 보수적으로 말한다."""
        if not self.is_ready():
            return None
        return min(self._refreshed[s] for s in SCOPES)

    def age_seconds(self, scope: str) -> float:
        """마지막 성공 적재 이후 경과 초.

        한 번도 못 했으면 기동 이후 경과 초(경보가 울리도록).
        """
        base = self._refreshed.get(scope, self._started_at)
        return max(0.0, (self._clock() - base).total_seconds())

    # --- 쓰기(적재기만) ---------------------------------------------------------
    def update(
        self,
        scope: str,
        tables: Sequence[str],
        mutate: Callable[[sqlite3.Connection], None],
    ) -> None:
        """현재 내용을 복사한 새 연결에 `mutate`로 바뀐 부분을 적재하고, 잠근 뒤 교체한다.

        `mutate`나 그 뒤 단계에서 예외가 나면 아무것도 바뀌지 않는다(이전 세대 유지).
        """
        if scope not in SCOPES:
            raise ValueError(f"unknown scope: {scope}")
        with self._write_lock:
            conn = sqlite3.connect(":memory:", check_same_thread=False)
            try:
                current = self._current
                if current is None:
                    schema.create_schema(conn)
                else:
                    current.conn.backup(conn)
                moment = self._clock()
                stamp = format_minute(moment)
                with conn:  # 하나의 트랜잭션: 실패하면 전부 되돌린다
                    mutate(conn)
                    conn.executemany(
                        schema.META_INSERT,
                        [{"table_name": t, "scope": scope, "refreshed_at": stamp} for t in tables],
                    )
                lock_connection(conn)
            except BaseException:
                conn.close()
                raise
            self._publish(_Generation(conn), scope, moment)

    def _publish(self, new: _Generation, scope: str, moment: datetime) -> None:
        with self._state_lock:
            old, self._current = self._current, new
            self._refreshed[scope] = moment
            if old is not None:
                old.retired = True
                if old.inflight == 0:
                    old.conn.close()

    # --- 읽기(질의 쪽) ----------------------------------------------------------
    @contextmanager
    def reader(self) -> Iterator[sqlite3.Connection]:
        """잠긴(읽기 전용) 연결을 빌려준다. 블록이 끝나면 반납한다."""
        with self._state_lock:
            generation = self._current
            if generation is None or not self.is_ready():
                raise SnapshotNotReady("snapshot not loaded yet")
            generation.inflight += 1
        try:
            yield generation.conn
        finally:
            with self._state_lock:
                generation.inflight -= 1
                if generation.retired and generation.inflight == 0:
                    generation.conn.close()

    def close(self) -> None:
        with self._state_lock:
            generation, self._current = self._current, None
            if generation is not None:
                generation.retired = True
                if generation.inflight == 0:
                    generation.conn.close()
