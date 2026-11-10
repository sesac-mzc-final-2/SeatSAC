import pytest

from seatsac_ai.snapshot import mapper, schema

from .snapshot_fakes import movie_detail, movie_summary, screening, theater


def test_screening_row_converts_to_kst_and_start_hour():
    row = mapper.screening_row(screening(starts="2026-11-06T11:30:00+00:00"), "2026-11-06 12:00")
    assert row["starts_at"] == "2026-11-06 20:30"
    assert row["start_hour"] == 20
    assert row["fetched_at"] == "2026-11-06 12:00"


def test_screening_row_has_exactly_schema_columns_and_no_poster():
    row = mapper.screening_row(screening(posterUrl="x"), "t")
    assert tuple(row) == schema.SCREENING_COLUMNS


def test_late_night_keeps_business_date():
    row = mapper.screening_row(
        screening(starts="2026-11-07T01:10:00+09:00", businessDate="2026-11-06"), "t"
    )
    assert row["business_date"] == "2026-11-06"
    assert row["start_hour"] == 1


def test_naive_time_is_rejected():
    with pytest.raises(mapper.MappingError):
        mapper.screening_row(screening(starts="2026-11-06T20:00:00"), "t")


def test_missing_required_is_rejected():
    bad = screening()
    del bad["movie"]
    with pytest.raises(mapper.MappingError):
        mapper.screening_row(bad, "t")


def test_movie_row_detail_and_summary():
    detail = mapper.movie_row(movie_detail())
    assert detail["genres"] == "드라마,액션"
    assert tuple(detail) == schema.MOVIE_COLUMNS
    assert "poster" not in "".join(detail)
    assert detail["runtime_minutes"] == 112  # 실제 러닝타임(상세에만 있음)
    summary = mapper.movie_row(movie_summary())
    assert summary["synopsis"] is None and summary["genres"] is None
    assert summary["runtime_minutes"] is None


def test_theater_row():
    assert mapper.theater_row(theater(3))["has_layout"] == 1
