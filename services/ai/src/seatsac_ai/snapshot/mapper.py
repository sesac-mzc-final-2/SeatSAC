"""공개 API 응답(JSON) → 스냅샷 표 한 줄.

순수 함수만 둔다(네트워크·DB를 건드리지 않음). 그래서 API 없이 단위 테스트가 된다.
표에 넣을 칸을 하나씩 골라 담는 허용 목록 방식이다 — API에 새 칸이 생겨도 자동으로 들어오지 않고,
`posterUrl`·배치도 같은 값은 애초에 꺼내지 않는다.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import datetime, timedelta, timezone
from typing import Any

# KST는 일광절약시간이 없는 고정 +09:00이다.
# ZoneInfo는 이미지에 tzdata가 없으면 실패할 수 있어 쓰지 않는다.
KST = timezone(timedelta(hours=9), "KST")


class MappingError(ValueError):
    """응답 모양이 계약과 달라 표 한 줄을 만들 수 없다."""


def _require(data: Mapping[str, Any], key: str, where: str) -> Any:
    try:
        value = data[key]
    except (KeyError, TypeError) as exc:
        raise MappingError(f"{where}: '{key}' 없음") from exc
    if value is None:
        raise MappingError(f"{where}: '{key}'가 null")
    return value


def kst_datetime(value: str, where: str = "datetime") -> datetime:
    """ISO 8601 문자열(오프셋 필수) → KST 시각."""
    try:
        parsed = datetime.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise MappingError(f"{where}: 시각 형식 오류 {value!r}") from exc
    if parsed.tzinfo is None:
        # 오프셋이 없으면 어느 시간대인지 알 수 없다. 추측하지 않고 거부한다.
        raise MappingError(f"{where}: 시간대(오프셋) 없음 {value!r}")
    return parsed.astimezone(KST)


def format_minute(moment: datetime) -> str:
    """`YYYY-MM-DD HH:MM` (KST). 스냅샷의 모든 시각 칸이 쓰는 형식."""
    return moment.astimezone(KST).strftime("%Y-%m-%d %H:%M")


def _join(values: Sequence[str] | None) -> str | None:
    if not values:
        return None
    return ",".join(str(v) for v in values)


def movie_row(data: Mapping[str, Any]) -> dict[str, Any]:
    """`GET /movies/{id}`(상세) 또는 `GET /movies` 목록 원소(요약) → movie 한 줄.

    요약에는 줄거리·감독·장르가 없으므로 그 칸은 None이 된다(상세 호출이 실패했을 때의 대체).
    """
    return {
        "movie_id": int(_require(data, "movieId", "movie")),
        "title": str(_require(data, "title", "movie")),
        "title_en": data.get("titleEn"),
        "view_grade": data.get("viewGrade"),
        "slot_minutes": data.get("slotMinutes"),
        "release_date": data.get("releaseDate"),
        "director": data.get("director"),
        "genres": _join(data.get("genres")),
        "synopsis": data.get("synopsis"),
    }


def theater_row(data: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "theater_id": int(_require(data, "theaterId", "theater")),
        "name": str(_require(data, "name", "theater")),
        "region": data.get("region"),
        "has_layout": 1 if data.get("hasLayout") else 0,
    }


def screening_row(data: Mapping[str, Any], fetched_at: str) -> dict[str, Any]:
    """`GET /screenings` 원소 → screening 한 줄. `fetched_at`은 가져온 시각(KST 문자열)."""
    where = "screening"
    movie = _require(data, "movie", where)
    theater = _require(data, "theater", where)
    auditorium = _require(data, "auditorium", where)
    starts = kst_datetime(_require(data, "startsAt", where), "startsAt")
    ends = kst_datetime(_require(data, "endsAt", where), "endsAt")
    return {
        "screening_id": int(_require(data, "screeningId", where)),
        "movie_id": int(_require(movie, "movieId", "screening.movie")),
        "theater_id": int(_require(theater, "theaterId", "screening.theater")),
        "auditorium_id": int(_require(auditorium, "auditoriumId", "screening.auditorium")),
        "auditorium_name": auditorium.get("name"),
        "screen_type": auditorium.get("screenType"),
        "business_date": str(_require(data, "businessDate", where)),
        "starts_at": format_minute(starts),
        "ends_at": format_minute(ends),
        # 자정 넘어 시작하는 심야 회차는 business_date(영업일)와 starts_at의 날짜가 다를 수 있다.
        "start_hour": starts.hour,
        "film_format": data.get("filmFormat"),
        "total_seats": data.get("totalSeats"),
        "remaining_seats": data.get("remainingSeats"),
        "remaining_seats_source": data.get("remainingSeatsSource"),
        "fetched_at": fetched_at,
    }
