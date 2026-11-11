"""SQL 테스트용 스냅샷. 값을 알고 있어서 결과 행 수를 정확히 맞출 수 있다.

기준: 오늘 = 2026-11-17(화), 지금 = 2026-11-17 19:40 (schema.yaml 예시와 같은 가정)
"""

from __future__ import annotations

from seatsac_ai.snapshot import schema
from seatsac_ai.snapshot.store import SnapshotStore

TODAY = "2026-11-17"
NOW = "2026-11-17 19:40"
SYSTEM = {"today": TODAY, "now": NOW}

MOVIES = [
    # movie_id, title, view_grade, slot_minutes, genres
    (1, "별의 항해", "12", 140, "SF,모험"),
    (2, "한강의 밤", "15", 115, "스릴러"),
    (3, "고양이 탐정", "ALL", 95, "애니메이션,코미디"),
    (4, "철의 심장", "15", 130, "액션"),
]
THEATERS = [(10, "건대입구", "서울"), (20, "강남", "서울")]
SCREENINGS = [
    # id, movie, theater, 관, 관종류, 영업일, 시작, 시작시, 잔여, 전체, 출처
    (1, 1, 10, "1관", "NORMAL", TODAY, "2026-11-17 18:00", 18, 50, 100, "BOOKING"),  # 이미 시작
    (2, 1, 10, "1관", "NORMAL", TODAY, "2026-11-17 20:00", 20, 30, 100, "BOOKING"),
    (3, 2, 10, "2관", "RECLINER", TODAY, "2026-11-17 21:00", 21, 4, 40, "BOOKING"),  # 10%
    (4, 3, 20, "1관", "NORMAL", TODAY, "2026-11-17 19:50", 19, 80, 100, "BOOKING"),
    (5, 4, 20, "2관", "NORMAL", TODAY, "2026-11-17 22:30", 22, 100, 100, "TOTAL"),  # 잔여석 불확실
    (6, 2, 20, "1관", "NORMAL", TODAY, "2026-11-18 00:30", 0, 60, 100, "BOOKING"),  # 자정 넘은 심야
    (7, 4, 10, "2관", "RECLINER", "2026-11-18", "2026-11-18 14:00", 14, 20, 40, "BOOKING"),
    (8, 3, 10, "1관", "NORMAL", "2026-11-18", "2026-11-18 10:00", 10, 90, 100, "BOOKING"),
    (9, 4, 20, "1관", "NORMAL", "2026-11-21", "2026-11-21 15:00", 15, 70, 100, "BOOKING"),
]


def _rows(table_columns, values):
    return [dict(zip(table_columns, v, strict=True)) for v in values]


def _fill(conn):
    conn.executemany(
        schema.MOVIE_INSERT,
        [
            {c: None for c in schema.MOVIE_COLUMNS}
            | {"movie_id": i, "title": t, "view_grade": g, "slot_minutes": m, "genres": ge}
            | ({"runtime_minutes": m - 15} if i != 4 else {})
            for i, t, g, m, ge in MOVIES
        ],
    )
    conn.executemany(
        schema.THEATER_INSERT,
        [{"theater_id": i, "name": n, "region": r, "has_layout": 1} for i, n, r in THEATERS],
    )
    conn.executemany(
        schema.SCREENING_INSERT,
        [
            {
                "screening_id": i,
                "movie_id": mv,
                "theater_id": th,
                "auditorium_id": th * 10 + int(aud[0]),
                "auditorium_name": aud,
                "screen_type": st,
                "business_date": bd,
                "starts_at": sa,
                "ends_at": sa,
                "start_hour": sh,
                "film_format": "2D",
                "total_seats": tot,
                "remaining_seats": rem,
                "remaining_seats_source": src,
                "fetched_at": NOW,
            }
            for i, mv, th, aud, st, bd, sa, sh, rem, tot, src in SCREENINGS
        ],
    )


def loaded_store() -> SnapshotStore:
    store = SnapshotStore()
    store.update("other", ("movie", "theater", "screening"), _fill)
    store.update("today", ("screening",), lambda conn: None)
    return store
