import pytest

from seatsac_ai.sql.validator import (
    FORBIDDEN_FUNCTION,
    FORBIDDEN_OBJECT,
    MULTI_STATEMENT,
    NOT_SELECT,
    PARSE_ERROR,
    SqlRejected,
    ValidatedSql,
    exclude_started,
    validate,
    with_screening_id,
)


@pytest.mark.parametrize(
    "sql",
    [
        "SELECT title FROM movie",
        "select title from MOVIE where view_grade = '15'",
        "SELECT m.title FROM screening s JOIN movie m ON m.movie_id = s.movie_id",
        "WITH t AS (SELECT * FROM theater) SELECT name FROM t",
        "SELECT title FROM movie UNION SELECT name FROM theater",
        "SELECT title FROM movie WHERE movie_id IN (SELECT movie_id FROM screening)",
        "SELECT strftime('%H', starts_at), count(*) FROM screening GROUP BY 1",
        "SELECT * FROM main.movie",
        "SELECT title FROM movie;",
    ],
)
def test_accepts_plain_selects(sql):
    assert validate(sql).sql.upper().startswith(("SELECT", "WITH"))


@pytest.mark.parametrize(
    ("sql", "reason"),
    [
        ("DELETE FROM movie", NOT_SELECT),
        ("UPDATE movie SET title = 'x'", NOT_SELECT),
        ("DROP TABLE movie", NOT_SELECT),
        ("INSERT INTO movie (movie_id, title) VALUES (9, 'x')", NOT_SELECT),
        ("WITH a AS (SELECT 1) DELETE FROM movie", NOT_SELECT),
        ("VACUUM", NOT_SELECT),
        ("PRAGMA query_only = OFF", FORBIDDEN_FUNCTION),
        ("ATTACH DATABASE ':memory:' AS x", FORBIDDEN_FUNCTION),
        ("SELECT 1; DELETE FROM movie", MULTI_STATEMENT),
        ("SELECT * FROM snapshot_meta", FORBIDDEN_OBJECT),
        ("SELECT * FROM sqlite_master", FORBIDDEN_OBJECT),
        ('SELECT * FROM "SQLITE_MASTER"', FORBIDDEN_OBJECT),
        ("SELECT * FROM temp.movie", FORBIDDEN_OBJECT),
        ("SELECT * FROM movie WHERE movie_id IN (SELECT 1 FROM snapshot_meta)", FORBIDDEN_OBJECT),
        ("SELECT * FROM pragma_table_info('movie')", FORBIDDEN_FUNCTION),
        ("SELECT * FROM json_each('[1]')", FORBIDDEN_FUNCTION),
        ("SELECT load_extension('x')", FORBIDDEN_FUNCTION),
        ("SELECT randomblob(1000000000)", FORBIDDEN_FUNCTION),
        ("SELECT sqlite_compileoption_used('x')", FORBIDDEN_FUNCTION),
        (
            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT x FROM c",
            FORBIDDEN_FUNCTION,
        ),
        ("", PARSE_ERROR),
        ("SELECT (", PARSE_ERROR),
    ],
)
def test_rejects(sql, reason):
    with pytest.raises(SqlRejected) as caught:
        validate(sql)
    assert caught.value.reason == reason


def test_forbidden_object_names_the_table():
    with pytest.raises(SqlRejected) as caught:
        validate("SELECT * FROM snapshot_meta")
    assert caught.value.object == "snapshot_meta"


def test_cte_may_not_hide_a_forbidden_table():
    with pytest.raises(SqlRejected):
        validate("WITH movie AS (SELECT * FROM snapshot_meta) SELECT * FROM movie")


def test_adds_limit_when_missing():
    v = validate("SELECT title FROM movie", max_rows=500)
    assert v.sql.endswith("LIMIT 500") and v.limit_capped


def test_keeps_small_limit():
    v = validate("SELECT title FROM movie LIMIT 3")
    assert v.sql.endswith("LIMIT 3") and not v.limit_capped


def test_lowers_big_limit():
    v = validate("SELECT title FROM movie LIMIT 100000")
    assert v.sql.endswith("LIMIT 500") and v.limit_capped


def test_parameter_limit_is_bounded():
    v = validate("SELECT title FROM movie LIMIT :n")
    assert v.sql.endswith("LIMIT MAX(0, MIN(:n, 500))") and v.limit_capped  # 음수 → 0


def test_reports_tables_read():
    v = validate("SELECT 1 FROM screening s JOIN movie m ON m.movie_id = s.movie_id")
    assert v.tables == {"screening", "movie"}


def test_sql_template_hides_values_for_logs():
    from seatsac_ai.sql.validator import sql_template

    out = sql_template("SELECT title FROM movie WHERE title = '내 전화 010-1234' LIMIT 3")
    assert out == "SELECT title FROM movie WHERE title = ? LIMIT ?"


@pytest.mark.parametrize(
    ("sql", "expected"),
    [
        # FROM 은 WHERE 에 AND 로
        (
            "SELECT s.starts_at FROM screening s WHERE s.starts_at >= '2026-11-17 20:00'",
            "SELECT s.starts_at FROM screening AS s "
            "WHERE s.starts_at >= '2026-11-17 20:00' AND s.starts_at > :now LIMIT 500",
        ),
        # 별칭 없음 · WHERE 없음
        (
            "SELECT count(*) FROM screening",
            "SELECT COUNT(*) FROM screening WHERE screening.starts_at > :now LIMIT 500",
        ),
        # JOIN 은 ON 에
        (
            "SELECT m.title FROM movie m LEFT JOIN screening s ON s.movie_id = m.movie_id",
            "SELECT m.title FROM movie AS m LEFT JOIN screening AS s "
            "ON s.movie_id = m.movie_id AND s.starts_at > :now LIMIT 500",
        ),
        # 서브쿼리 안의 screening 도
        (
            "SELECT title FROM movie WHERE movie_id IN (SELECT movie_id FROM screening)",
            "SELECT title FROM movie WHERE movie_id IN "
            "(SELECT movie_id FROM screening WHERE screening.starts_at > :now) LIMIT 500",
        ),
        # screening 을 읽지 않으면 그대로
        ("SELECT title FROM movie", "SELECT title FROM movie LIMIT 500"),
    ],
)
def test_exclude_started(sql, expected):
    assert exclude_started(validate(sql)).sql == expected


def test_exclude_started_filters_inside_a_cte_not_the_cte_reference():
    sql = "WITH upcoming AS (SELECT movie_id FROM screening) SELECT count(*) FROM upcoming"
    out = exclude_started(validate(sql)).sql
    assert out == (
        "WITH upcoming AS (SELECT movie_id FROM screening WHERE screening.starts_at > :now) "
        "SELECT COUNT(*) FROM upcoming LIMIT 500"
    )


@pytest.mark.parametrize(
    ("sql", "expected"),
    [
        # 행 하나 = 회차 하나 → 끝에 붙인다(ORDER BY·LIMIT 은 그대로)
        (
            "SELECT m.title AS movie_title, s.starts_at FROM screening s "
            "JOIN movie m ON m.movie_id = s.movie_id ORDER BY 2 LIMIT 5",
            "SELECT m.title AS movie_title, s.starts_at, s.screening_id AS _screening_id "
            "FROM screening AS s JOIN movie AS m ON m.movie_id = s.movie_id ORDER BY 2 LIMIT 5",
        ),
        # 별칭 없음 · JOIN 쪽의 screening
        (
            "SELECT title, starts_at FROM movie JOIN screening USING (movie_id)",
            "SELECT title, starts_at, screening.screening_id AS _screening_id "
            "FROM movie JOIN screening USING (movie_id) LIMIT 500",
        ),
        # 창 함수는 행 수를 줄이지 않는다
        (
            "SELECT s.starts_at, count(*) OVER () AS total FROM screening s",
            "SELECT s.starts_at, COUNT(*) OVER () AS total, s.screening_id AS _screening_id "
            "FROM screening AS s LIMIT 500",
        ),
        # `*` · `s.*` 는 screening 의 starts_at 이 그대로 나온다(movie·theater 에는 그 칸이 없다)
        (
            "SELECT * FROM screening",
            "SELECT *, screening.screening_id AS _screening_id FROM screening LIMIT 500",
        ),
        (
            "SELECT s.*, m.title FROM screening s JOIN movie m ON m.movie_id = s.movie_id",
            "SELECT s.*, m.title, s.screening_id AS _screening_id "
            "FROM screening AS s JOIN movie AS m ON m.movie_id = s.movie_id LIMIT 500",
        ),
        # CTE 가 있어도 시작 시각이 바깥 screening 의 것이면 된다
        (
            "WITH hit AS (SELECT movie_id FROM movie WHERE view_grade = 'ALL') "
            "SELECT s.starts_at AS starts_at FROM hit "
            "JOIN screening s ON s.movie_id = hit.movie_id",
            "WITH hit AS (SELECT movie_id FROM movie WHERE view_grade = 'ALL') "
            "SELECT s.starts_at AS starts_at, s.screening_id AS _screening_id "
            "FROM hit JOIN screening AS s ON s.movie_id = hit.movie_id LIMIT 500",
        ),
    ],
)
def test_with_screening_id_adds_a_trailing_column(sql, expected):
    assert with_screening_id(validate(sql)).sql == expected


@pytest.mark.parametrize(
    "sql",
    [
        "SELECT title FROM movie",  # screening 을 읽지 않는다
        "SELECT count(*) FROM screening",  # 집계
        "SELECT s.screen_type, min(s.starts_at) FROM screening s GROUP BY s.screen_type",
        "SELECT DISTINCT s.starts_at FROM screening s",
        "SELECT starts_at FROM screening UNION SELECT starts_at FROM screening",
        # screening 이 서브쿼리·CTE 뒤에 있다
        "SELECT starts_at FROM (SELECT starts_at FROM screening)",
        "WITH up AS (SELECT starts_at FROM screening) SELECT starts_at FROM up",
        "SELECT title FROM movie WHERE movie_id IN (SELECT movie_id FROM screening)",
        # 두 번 나오면 어느 회차인지 정할 수 없다
        "SELECT a.starts_at FROM screening a JOIN screening b ON b.movie_id = a.movie_id",
        # 표에 시작 시각이 없으면 어느 회차인지 보고 고를 수 없다
        "SELECT m.title FROM screening s JOIN movie m ON m.movie_id = s.movie_id",
        "SELECT m.* FROM screening s JOIN movie m ON m.movie_id = s.movie_id",
        # 표의 starts_at 이 그 회차의 시작 시각이 아니다 — 시각과 링크의 회차가 달라진다
        "WITH nxt AS (SELECT movie_id, min(starts_at) AS starts_at FROM screening "
        "GROUP BY movie_id) "
        "SELECT m.title, nxt.starts_at, s.auditorium_name FROM nxt "
        "JOIN screening s ON s.movie_id = nxt.movie_id JOIN movie m ON m.movie_id = s.movie_id",
        "SELECT s.ends_at AS starts_at FROM screening s",
        "SELECT x.starts_at, s.auditorium_name FROM screening s "
        "JOIN (SELECT movie_id, min(starts_at) AS starts_at FROM screening GROUP BY movie_id) x "
        "ON x.movie_id = s.movie_id",
        "SELECT substr(s.starts_at, 12) AS starts_at FROM screening s",
        # `*` 가 서브쿼리와 섞이면 starts_at 이 어느 쪽 것인지 알 수 없다
        "SELECT * FROM screening s "
        "JOIN (SELECT movie_id, min(starts_at) AS starts_at FROM screening GROUP BY movie_id) x "
        "ON x.movie_id = s.movie_id",
        # 덧붙일 칸과 같은 이름을 이미 쓰고 있다
        "SELECT s.movie_id AS _screening_id, s.starts_at FROM screening s",
        # sqlglot 이 집계로 알아보지 못하는 SQLite 집계 함수
        "SELECT s.starts_at, total(s.remaining_seats) FROM screening s",
        "SELECT s.starts_at, group_concat(s.auditorium_name) FROM screening s",
    ],
)
def test_with_screening_id_skips_rows_that_are_not_one_screening(sql):
    assert with_screening_id(validate(sql)) is None


def test_with_screening_id_gives_up_quietly_when_the_sql_cannot_be_parsed():
    """다시 읽지 못하면 덧붙이지 않는다(그대로 실행하게 None) — 예외를 올리지 않는다."""
    broken = ValidatedSql("SELECT FROM WHERE (", frozenset({"screening"}), False)
    assert with_screening_id(broken) is None


@pytest.mark.parametrize(
    "sql",
    [
        "SELECT * FROM (WITH sqlite_master AS (SELECT 1) SELECT 1), sqlite_master",
        "SELECT * FROM (WITH snapshot_meta AS (SELECT 1) SELECT 1), snapshot_meta",
        "SELECT (SELECT 1 FROM (WITH x AS (SELECT 1) SELECT 1)) FROM x",
    ],
)
def test_with_name_is_only_visible_where_it_is_defined(sql):
    """#91 리뷰: 안쪽 서브쿼리의 WITH 이름으로 바깥의 실제 표를 가릴 수 없다."""
    with pytest.raises(SqlRejected) as caught:
        validate(sql)
    assert caught.value.reason == FORBIDDEN_OBJECT


def test_with_name_still_works_in_its_own_query_and_in_later_ctes():
    validate("WITH a AS (SELECT movie_id FROM movie), b AS (SELECT * FROM a) SELECT * FROM b")
    validate("SELECT * FROM (WITH a AS (SELECT title FROM movie) SELECT * FROM a)")
