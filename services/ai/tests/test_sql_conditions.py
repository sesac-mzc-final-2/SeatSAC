"""SQL 조건 분석기: 어느 날짜·극장·영화를 조회하는지, 잔여석을 읽는지. 모르면 None(판단 보류)."""

import pytest

from seatsac_ai.resources import presets
from seatsac_ai.sql.conditions import analyze
from seatsac_ai.sql.validator import validate

PARAMS = {
    "today": "2026-11-18", "d1": "2026-11-19", "now": "2026-11-18 12:00",
    "date": "2026-11-19", "theater": "건대입구", "movie": "오디세이", "time": "20:00", "n": 5,
}  # fmt: skip
JOINS = (
    "FROM screening s JOIN movie m ON m.movie_id = s.movie_id "
    "JOIN theater t ON t.theater_id = s.theater_id "
)


def cond(sql, params=PARAMS):
    return analyze(validate(sql), params)


def test_reads_date_theater_and_seats():
    c = cond(
        "SELECT m.title, s.starts_at, s.remaining_seats " + JOINS
        + "WHERE s.business_date = '2026-11-19' AND t.name = '건대입구' "
        "ORDER BY s.remaining_seats DESC"
    )  # fmt: skip
    assert c.dates == {"2026-11-19"} and c.has_theater("건대입구") is True
    assert c.reads_seats and c.seat_outputs == {"remaining_seats"}
    assert c.has_date("2026-11-19") is True and c.has_date("2026-11-18") is False


def test_placeholders_are_read_from_params():
    c = cond("SELECT m.title " + JOINS + "WHERE s.business_date = :date AND t.name = :theater")
    assert c.dates == {"2026-11-19"} and c.has_theater("건대입구") is True
    assert not c.reads_seats


def test_like_and_in():
    c = cond(
        "SELECT t.name, s.remaining_seats AS seats " + JOINS
        + "WHERE m.title LIKE '%오디세이%' AND s.business_date IN ('2026-11-21', '2026-11-22')"
    )  # fmt: skip
    assert c.has_movie("오디세이") is True and c.dates == {"2026-11-21", "2026-11-22"}
    assert c.seat_outputs == {"seats"}  # 별칭으로 나온다


def test_or_and_range_are_unknown_not_missing():
    c = cond(
        "SELECT s.starts_at " + JOINS
        + "WHERE (s.business_date = :today OR s.business_date = :d1) "
        "AND s.starts_at >= '2026-11-19 20:00'"
    )  # fmt: skip
    assert c.dates is None and c.has_date("2026-11-19") is None  # OR 안의 날짜: 판단 보류
    assert c.has_theater("건대입구") is False  # 극장 조건은 아예 없다 → 빠졌다


def test_theater_id_condition():
    c = cond("SELECT * FROM screening WHERE theater_id IN (4, 16)")
    assert c.theater_ids == {4, 16} and c.has_theater("건대입구", 4) is True
    assert c.reads_seats  # * 는 잔여석을 읽는 것으로 본다


def test_join_keys_are_not_conditions():
    c = cond("SELECT m.title " + JOINS + "WHERE s.business_date = :today")
    assert "theater" not in c.referenced and "movie" not in c.referenced


def test_seat_aggregate_is_flagged():
    c = cond("SELECT sum(remaining_seats) FROM screening WHERE business_date = :today")
    assert c.reads_seats and c.seat_aggregate
    assert c.seat_outputs == {"SUM(remaining_seats)"}  # 별칭 없으면 SQL 글자가 칸 이름


SEAT_PRESETS = {"most_remaining_today", "almost_sold_out_today"}


@pytest.mark.parametrize("preset", presets(), ids=lambda p: p.preset_id)
def test_only_seat_presets_read_seats(preset):
    """잔여석은 물었을 때만: 잔여석 프리셋만 잔여석을 읽는다(질문 시점 갱신도 그때만 돈다)."""
    assert cond(preset.sql).reads_seats == (preset.preset_id in SEAT_PRESETS)


def test_starts_at_alone_is_not_a_date_condition():
    """모든 SQL에 붙는 「starts_at > 지금」은 사용자가 말한 날짜가 아니다."""
    c = cond("SELECT s.starts_at, s.remaining_seats FROM screening s WHERE s.starts_at > :now")
    assert c.has_date("2026-11-18") is False and "date" not in c.referenced
