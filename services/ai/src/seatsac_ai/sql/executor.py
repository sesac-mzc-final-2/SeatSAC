"""읽기 전용 실행기 (WBS 3.4.2 · 기능명세서 v4 15-2 7단계).

검증을 통과한 SQL(`ValidatedSql`)만 받아 스냅샷에서 실행한다.
- 시간 상한: progress handler가 5초(또는 요청의 남은 시간)를 넘으면 SQLite를 멈춘다
- 행 상한: fetchmany(500)
- BLOB 결과는 SQL_ERROR(응답 JSON으로 바꿀 수 없다)
- 쓰기는 스냅샷 연결의 authorizer가 막는다(여기서 또 막지 않는다)

동시 실행: 스냅샷의 한 세대 연결을 여러 질의가 함께 쓰고, progress handler는 **연결에 하나**만
걸 수 있다(store.py 참고). 그래서 실행을 잠금으로 한 줄로 세운다. 질의는 보통 수 ms라
대기는 짧고, 최악은 앞 질의의 상한(5초)만큼이다.
"""

from __future__ import annotations

import asyncio
import sqlite3
import threading
import time
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from seatsac_ai.common.metrics import AI_SQL_EXEC_SECONDS
from seatsac_ai.snapshot.store import SnapshotStore
from seatsac_ai.sql.validator import ValidatedSql

SQL_ERROR = "SQL_ERROR"
TIMEOUT = "TIMEOUT"

_PROGRESS_STEPS = 1000  # SQLite 가상 머신 명령 1000개마다 시간 확인
_lock = threading.Lock()


class SqlExecutionError(Exception):
    """실행 실패. reason = SQL_ERROR(Claude에 오류 문구를 붙여 1회 재요청) · TIMEOUT."""

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(f"{reason}: {message}")
        self.reason = reason
        self.message = message


@dataclass(frozen=True)
class QueryResult:
    columns: list[dict[str, str]]  # [{name, type}] — type: text·integer·real (계약 Column)
    rows: list[list[Any]]
    row_count: int
    truncated: bool
    as_of: datetime | None  # 스냅샷 기준 시각


def execute(
    store: SnapshotStore,
    query: ValidatedSql,
    params: Mapping[str, Any] | None = None,
    *,
    timeout_seconds: float = 5.0,
    max_rows: int = 500,
) -> QueryResult:
    deadline = time.monotonic() + timeout_seconds
    started = time.perf_counter()
    with store.reader() as conn, _lock:
        conn.set_progress_handler(lambda: 1 if time.monotonic() > deadline else 0, _PROGRESS_STEPS)
        try:
            cursor = conn.execute(query.sql, dict(params or {}))
            rows = [list(r) for r in cursor.fetchmany(max_rows)]
            names = [d[0] for d in cursor.description or ()]
            # BLOB(bytes)은 응답 JSON으로 바꿀 수 없다(UnicodeDecodeError → 500).
            # 스냅샷에는 BLOB 칸이 없으니 SQL 오류로 돌려 Claude 재요청 경로를 탄다(#91 리뷰)
            if any(isinstance(v, bytes) for row in rows for v in row):
                raise SqlExecutionError(SQL_ERROR, "BLOB 값은 돌려줄 수 없습니다")
        except sqlite3.OperationalError as exc:
            if "interrupted" in str(exc):
                message = f"{timeout_seconds:g}초 안에 끝나지 않았습니다"
                raise SqlExecutionError(TIMEOUT, message) from exc
            raise SqlExecutionError(SQL_ERROR, str(exc)) from exc
        except sqlite3.Error as exc:
            raise SqlExecutionError(SQL_ERROR, str(exc)) from exc
        finally:
            conn.set_progress_handler(None, 0)
            AI_SQL_EXEC_SECONDS.observe(time.perf_counter() - started)
    return QueryResult(
        columns=[{"name": n, "type": _column_type(rows, i)} for i, n in enumerate(names)],
        rows=rows,
        row_count=len(rows),
        truncated=query.limit_capped and len(rows) >= max_rows,
        as_of=store.as_of(),
    )


async def execute_async(
    store: SnapshotStore, query: ValidatedSql, params=None, **kw
) -> QueryResult:
    """이벤트 루프를 막지 않게 스레드에서 실행한다(3.4.9 리뷰 반영과 같은 이유)."""
    return await asyncio.to_thread(execute, store, query, params, **kw)


def _column_type(rows: list[list[Any]], index: int) -> str:
    # SQLite는 칸마다 값 종류가 섞일 수 있다. 처음 나오는 NULL 아닌 값으로 정한다
    for row in rows:
        value = row[index]
        if value is None:
            continue
        if isinstance(value, bool | int):
            return "integer"
        if isinstance(value, float):
            return "real"
        return "text"
    return "text"
