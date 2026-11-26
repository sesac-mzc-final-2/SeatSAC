"""정제된 수요 저장·기간 통계. 연결과 시간 경계 생성은 호출자가 맡는다."""

from collections.abc import Callable
from contextlib import closing
from datetime import datetime
from json import dumps
from typing import Any

_PERIOD = "created_at >= %s AND created_at < %s"
_INSERT = """
INSERT INTO unmet_demand (category, reason, question_hash, slots, created_at)
VALUES (%s, %s, %s, %s, %s)
"""
_TOTAL = f"SELECT COUNT(*) FROM unmet_demand WHERE {_PERIOD}"
_BY_CATEGORY = f"""
SELECT category COLLATE utf8mb4_0900_bin AS exact_category, COUNT(*) AS demand_count
FROM unmet_demand
WHERE {_PERIOD}
GROUP BY exact_category
ORDER BY demand_count DESC, exact_category ASC
"""
_TOP = f"""
SELECT category COLLATE utf8mb4_0900_bin AS exact_category, reason, COUNT(*) AS demand_count
FROM unmet_demand
WHERE {_PERIOD} AND reason IS NOT NULL AND reason <> ''
GROUP BY exact_category, reason
ORDER BY demand_count DESC, exact_category ASC, reason ASC
LIMIT %s
"""


class MySqlDemandRepository:
    """작업마다 ai_app 연결을 하나 열고 닫는다. 호출 측에서 스레드로 실행한다."""

    def __init__(self, connect: Callable[[], Any]) -> None:
        self._connect = connect

    def insert(
        self,
        *,
        category: str,
        reason: str | None,
        question_hash: str,
        slots: dict | None,
        created_at: datetime,
    ) -> None:
        """이미 정제된 데이터만 받는다. created_at은 DB 규약의 naive UTC다."""
        slots_json = dumps(slots, ensure_ascii=False) if slots is not None else None
        with closing(self._connect()) as conn:
            try:
                with closing(conn.cursor()) as cursor:
                    cursor.execute(
                        _INSERT, (category, reason, question_hash, slots_json, created_at)
                    )
                conn.commit()
            except Exception:
                conn.rollback()
                raise

    def stats(self, start: datetime, end: datetime, limit: int) -> dict[str, Any]:
        """UTC [start, end) 기간의 집계 세 개를 동일한 데이터 시점에서 읽는다."""
        with closing(self._connect()) as conn:
            try:
                with closing(conn.cursor()) as cursor:
                    cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                    cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
                    cursor.execute(_TOTAL, (start, end))
                    (total,) = cursor.fetchone()
                    cursor.execute(_BY_CATEGORY, (start, end))
                    by_category = [
                        {"category": category, "count": count}
                        for category, count in cursor.fetchall()
                    ]
                    # reason은 마이그레이션의 utf8mb4_0900_bin 그대로, 글자 완전 일치로 묶는다.
                    cursor.execute(_TOP, (start, end, limit))
                    top = [
                        {"category": category, "reason": reason, "count": count}
                        for category, reason, count in cursor.fetchall()
                    ]
            finally:
                conn.rollback()  # 읽기 전용 트랜잭션 종료(실패 시도 포함)

        return {"total": total, "byCategory": by_category, "top": top}
