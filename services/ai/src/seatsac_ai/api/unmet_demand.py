"""범위 밖 질문 통계(6.4.4). 운영 관리자 인증은 ops-api, 호출 제한은 NetworkPolicy가 맡는다."""

from __future__ import annotations

import asyncio
import re
from datetime import UTC, date, datetime, time, timedelta
from typing import Annotated, Any

from fastapi import APIRouter, Query, Request

from seatsac_ai.common.errors import ApiError, ErrorCode, ErrorDetail, upstream_unavailable
from seatsac_ai.snapshot.mapper import KST

router = APIRouter(prefix="/internal/ai/unmet-demand", include_in_schema=False)

_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
_LIMIT = re.compile(r"0*(?:[1-9][0-9]?|100)")
_DEFAULT_LOOKBACK = timedelta(days=6)
_STATS_TIMEOUT_SECONDS = 2.0


def _invalid(field: str, reason: str) -> ApiError:
    return ApiError(
        400,
        ErrorCode.VALIDATION_FAILED,
        "Request validation failed",
        [ErrorDetail(field=field, reason=reason)],
    )


def _date(value: str, field: str) -> date:
    if _DATE.fullmatch(value) is None:
        raise _invalid(field, "FORMAT")
    try:
        day = date.fromisoformat(value)
    except ValueError:
        raise _invalid(field, "FORMAT") from None
    # KST 자정을 UTC로 옮기거나 종료 경계를 하루 늘릴 수 없는 날짜는 받지 않는다.
    if day in (date.min, date.max):
        raise _invalid(field, "RANGE")
    return day


def _utc_start(day: date) -> datetime:
    return datetime.combine(day, time.min, KST).astimezone(UTC).replace(tzinfo=None)


@router.get("/stats")
async def get_unmet_demand_stats(
    request: Request,
    from_date: Annotated[str | None, Query(alias="from")] = None,
    to_date: Annotated[str | None, Query(alias="to")] = None,
    limit: Annotated[str, Query()] = "20",
) -> dict[str, Any]:
    end_day = _date(to_date, "to") if to_date is not None else datetime.now(KST).date()
    if from_date is None:
        if end_day <= date.min + _DEFAULT_LOOKBACK:
            raise _invalid("from", "RANGE")
        start_day = end_day - _DEFAULT_LOOKBACK
    else:
        start_day = _date(from_date, "from")
    if not 0 <= (end_day - start_day).days < 31:
        raise _invalid("from", "RANGE")
    if _LIMIT.fullmatch(limit) is None:
        raise _invalid("limit", "RANGE")
    top_limit = int(limit.lstrip("0"))
    start_utc = _utc_start(start_day)
    end_utc = _utc_start(end_day + timedelta(days=1))

    demand = request.app.state.demand
    if demand is None:
        raise upstream_unavailable("ai_db")
    try:
        async with asyncio.timeout(_STATS_TIMEOUT_SECONDS):
            stats = await demand.stats(start_utc, end_utc, top_limit)
    except Exception:
        # DB 예외에 접속 정보·SQL이 포함될 수 있으므로 상세를 노출하지 않는다.
        raise upstream_unavailable("ai_db") from None

    return {
        "from": start_day.isoformat(),
        "to": end_day.isoformat(),
        "total": stats["total"],
        "byCategory": stats["byCategory"],
        "top": stats["top"],
    }
