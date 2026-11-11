"""스냅샷 표 양식. data-model 6-1 · 기능명세서 v4 17-1과 같은 칸이다.

이 파일이 표 양식의 단 하나뿐인 원본이다. mapper(칸 채우기)·store(교체)·sql 검증(허용 표)·
schema.yaml(Claude용 설명)이 모두 여기 이름을 기준으로 맞춘다.

넣지 않는 것(칸 자체를 만들지 않는다): `posterUrl`(포스터 표시 금지),
배치도·좌석 ID, 예매·결제·사용자.
시각은 모두 KST 문자열(`YYYY-MM-DD HH:MM`) — 질문이 KST 기준이라 Claude가 변환하지 않게 한다.
"""

from __future__ import annotations

import sqlite3

# Claude가 조회할 수 있는 표(SQL 검증의 허용 목록). snapshot_meta는 내부용이라 뺀다.
QUERYABLE_TABLES = ("movie", "theater", "screening")

MOVIE_COLUMNS = (
    "movie_id",
    "title",
    "title_en",
    "view_grade",
    "slot_minutes",
    "runtime_minutes",
    "release_date",
    "director",
    "genres",
    "synopsis",
)
THEATER_COLUMNS = ("theater_id", "name", "region", "has_layout")
SCREENING_COLUMNS = (
    "screening_id",
    "movie_id",
    "theater_id",
    "auditorium_id",
    "auditorium_name",
    "screen_type",
    "business_date",
    "starts_at",
    "ends_at",
    "start_hour",
    "film_format",
    "total_seats",
    "remaining_seats",
    "remaining_seats_source",
    "fetched_at",
)
META_COLUMNS = ("table_name", "scope", "refreshed_at")

_DDL = (
    """
    CREATE TABLE movie (
        movie_id     INTEGER PRIMARY KEY,
        title        TEXT NOT NULL,
        title_en     TEXT,
        view_grade   TEXT,
        slot_minutes INTEGER,
        runtime_minutes INTEGER,
        release_date TEXT,
        director     TEXT,
        genres       TEXT,
        synopsis     TEXT
    )
    """,
    """
    CREATE TABLE theater (
        theater_id INTEGER PRIMARY KEY,
        name       TEXT NOT NULL,
        region     TEXT,
        has_layout INTEGER NOT NULL
    )
    """,
    """
    CREATE TABLE screening (
        screening_id           INTEGER PRIMARY KEY,
        movie_id               INTEGER NOT NULL,
        theater_id             INTEGER NOT NULL,
        auditorium_id          INTEGER NOT NULL,
        auditorium_name        TEXT,
        screen_type            TEXT,
        business_date          TEXT NOT NULL,
        starts_at              TEXT NOT NULL,
        ends_at                TEXT NOT NULL,
        start_hour             INTEGER NOT NULL,
        film_format            TEXT,
        total_seats            INTEGER,
        remaining_seats        INTEGER,
        remaining_seats_source TEXT,
        fetched_at             TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE snapshot_meta (
        table_name   TEXT NOT NULL,
        scope        TEXT NOT NULL,
        refreshed_at TEXT NOT NULL,
        PRIMARY KEY (table_name, scope)
    )
    """,
)


def create_schema(conn: sqlite3.Connection) -> None:
    """빈 DB에 표 4개를 만든다."""
    for ddl in _DDL:
        conn.execute(ddl)


def insert_sql(table: str, columns: tuple[str, ...], on_conflict: str = "REPLACE") -> str:
    """이름 있는 자리표 INSERT. 기본은 같은 PK를 덮어쓰고(`REPLACE`), `IGNORE`면 기존 행을 둔다."""
    names = ", ".join(columns)
    marks = ", ".join(f":{c}" for c in columns)
    return f"INSERT OR {on_conflict} INTO {table} ({names}) VALUES ({marks})"


MOVIE_INSERT = insert_sql("movie", MOVIE_COLUMNS)
# 상세 호출이 실패한 영화: 이전 스냅샷의 행이 있으면 그대로 두고, 없을 때만 요약으로 넣는다
MOVIE_INSERT_IF_ABSENT = insert_sql("movie", MOVIE_COLUMNS, on_conflict="IGNORE")
THEATER_INSERT = insert_sql("theater", THEATER_COLUMNS)
SCREENING_INSERT = insert_sql("screening", SCREENING_COLUMNS)
META_INSERT = insert_sql("snapshot_meta", META_COLUMNS)
