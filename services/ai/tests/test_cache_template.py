"""SQL ↔ SQL 템플릿 (3.4.5). 값의 출처를 설명할 수 없으면 캐시하지 않는다."""

from datetime import date, datetime

import pytest

from seatsac_ai.cache.slots import extract
from seatsac_ai.cache.template import (
    TemplateError,
    anchors_present,
    make_template,
    preset_template,
    render,
    system_params,
)
from seatsac_ai.snapshot.mapper import KST

NOW = datetime(2026, 11, 17, 19, 40, tzinfo=KST)
TODAY = date(2026, 11, 17)
NOW_TEXT = "2026-11-17 19:40"


def ex(question):
    return extract(question, NOW, ["건대입구", "강남"], ["별의 항해"])


def test_time_date_and_now_become_placeholders():
    sql = (
        "SELECT title FROM screening s WHERE s.business_date = '2026-11-17' "
        "AND s.starts_at >= '2026-11-17 20:00' AND s.starts_at > '2026-11-17 19:40'"
    )
    t = make_template(sql, ex("오늘 20시 이후 영화"), TODAY, NOW_TEXT)
    assert ":today" in t.sql and ":time" in t.sql and ":now" in t.sql
    assert "2026-11-17" not in t.sql and t.anchors == ()


def test_tomorrow_becomes_d1_only_when_the_question_says_tomorrow():
    sql = (
        "SELECT 1 FROM screening WHERE business_date = '2026-11-18' "
        "AND starts_at > '2026-11-17 19:40'"
    )
    assert ":d1" in make_template(sql, ex("내일 영화"), TODAY, NOW_TEXT).sql
    with pytest.raises(TemplateError):  # "이번 주말" — 어느 날짜인지 질문 글자로 설명이 안 된다
        make_template(sql, ex("이번 주말 영화"), TODAY, NOW_TEXT)


def test_start_hour_compare_uses_the_time_slot():
    sql = "SELECT 1 FROM screening s WHERE s.start_hour >= 20 AND s.starts_at > '2026-11-17 19:40'"
    t = make_template(sql, ex("20시 이후"), TODAY, NOW_TEXT)
    assert "CAST(SUBSTR(:TIME, 1, 2) AS INTEGER)" in t.sql.upper()


def test_partial_like_keeps_the_slot():
    sql = "SELECT 1 FROM theater t WHERE t.name LIKE '%건대%'"
    t = make_template(sql, ex("건대 영화관"), TODAY, NOW_TEXT)
    assert ":theater" in t.sql and "'%'" in t.sql


def test_slot_not_used_in_sql_means_not_cacheable():
    sql = "SELECT 1 FROM screening WHERE theater_id = 20 AND starts_at > '2026-11-17 19:40'"
    with pytest.raises(TemplateError):
        make_template(sql, ex("강남 회차"), TODAY, NOW_TEXT)


def test_fixed_values_become_anchors():
    sql = "SELECT 1 FROM screening WHERE screen_type = 'RECLINER' AND remaining_seats < 10"
    t = make_template(sql, ex("리클라이너 매진 임박"), TODAY, NOW_TEXT)
    assert t.anchors == ("리클라이너", "10")
    assert anchors_present(t.anchors, "리클라이너관 잔여 10석 미만")
    assert not anchors_present(t.anchors, "일반관 잔여 10석 미만")


def test_preset_with_tomorrow_date_param():
    sql = "SELECT 1 FROM screening s WHERE s.business_date = :date AND s.starts_at > :now"
    t = preset_template(
        sql, {"date": "2026-11-18", "theater": "건대입구"}, ex("내일 건대 상영표"), TODAY
    )
    assert ":d1" in t.sql and ":date" not in t.sql


def test_preset_param_not_from_the_question_is_not_cacheable():
    with pytest.raises(TemplateError):
        preset_template("SELECT :time", {"time": "21:00"}, ex("오늘 20시 이후"), TODAY)


def test_render_fills_values_for_display():
    params = system_params(TODAY, NOW_TEXT) | {"time": "21:00"}
    sql = render("SELECT 1 WHERE a = :today AND b >= (:today || ' ' || :time)", params)
    assert "'2026-11-17'" in sql and "'21:00'" in sql and ":" not in sql.replace("21:00", "")


def test_system_params_cover_three_days_ahead():
    assert system_params(TODAY, NOW_TEXT)["d3"] == "2026-11-20"
