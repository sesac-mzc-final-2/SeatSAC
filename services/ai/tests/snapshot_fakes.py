"""스냅샷 테스트 공용 가짜 응답. 공개 API(catalog 계약)와 같은 모양이다."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))


def movie_summary(movie_id: int = 1, title: str = "영화1") -> dict:
    return {"movieId": movie_id, "title": title, "viewGrade": "ALL", "slotMinutes": 120}


def movie_detail(movie_id: int = 1, title: str = "영화1") -> dict:
    return {
        **movie_summary(movie_id, title),
        "titleEn": "Movie",
        "director": "감독",
        "genres": ["드라마", "액션"],
        "synopsis": "줄거리",
        "releaseDate": "2026-01-01",
        "posterUrl": "https://x/poster.jpg",
    }


def theater(theater_id: int = 10) -> dict:
    return {
        "theaterId": theater_id,
        "name": f"극장{theater_id}",
        "region": "서울",
        "hasLayout": True,
    }


def screening(screening_id: int = 100, starts: str = "2026-11-06T20:00:00+09:00", **kw) -> dict:
    data = {
        "screeningId": screening_id,
        "businessDate": starts[:10],
        "startsAt": starts,
        "endsAt": "2026-11-06T22:00:00+09:00",
        "filmFormat": "2D",
        "totalSeats": 100,
        "remainingSeats": 40,
        "remainingSeatsSource": "BOOKING",
        "movie": {"movieId": 1, "title": "영화1"},
        "theater": {"theaterId": 10, "name": "극장10"},
        "auditorium": {"auditoriumId": 7, "name": "1관", "screenType": "NORMAL"},
    }
    data.update(kw)
    return data


def kst(y, m, d, h=12, mi=0) -> datetime:
    return datetime(y, m, d, h, mi, tzinfo=KST)
