import pytest

from seatsac_ai.sql.validator import (
    FORBIDDEN_FUNCTION,
    FORBIDDEN_OBJECT,
    MULTI_STATEMENT,
    NOT_SELECT,
    PARSE_ERROR,
    SqlRejected,
    validate,
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
    assert v.sql.endswith("LIMIT MIN(:n, 500)") and v.limit_capped


def test_reports_tables_read():
    v = validate("SELECT 1 FROM screening s JOIN movie m ON m.movie_id = s.movie_id")
    assert v.tables == {"screening", "movie"}


def test_sql_template_hides_values_for_logs():
    from seatsac_ai.sql.validator import sql_template

    out = sql_template("SELECT title FROM movie WHERE title = '내 전화 010-1234' LIMIT 3")
    assert out == "SELECT title FROM movie WHERE title = ? LIMIT ?"
