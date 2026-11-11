"""프리셋 12개: 모양·검증 통과·실제 실행 결과(값을 아는 스냅샷 기준)."""

import re

import pytest

from seatsac_ai.resources import SLOT_KINDS, SYSTEM_PARAMS, presets
from seatsac_ai.sql.executor import execute
from seatsac_ai.sql.validator import validate

from .sql_fixtures import SYSTEM, loaded_store

# 각 프리셋에 넣을 슬롯 값과, sql_fixtures 기준 기대 행 수
CASES = {
    "today_after_time": ({"time": "20:00"}, 4),
    "theater_schedule": ({"date": "2026-11-18", "theater": "건대입구"}, 2),
    "movie_theaters": ({"movie": "철의 심장"}, 3),
    "most_remaining_today": ({"n": 2}, 2),
    "almost_sold_out_today": ({}, 1),
    "movies_on_date": ({"date": "2026-11-17"}, 4),
    "screen_type_today": ({}, 2),
    "movie_next_screening": ({"movie": "별의 항해"}, 1),
    "late_night_today": ({}, 2),
    "movie_runtime": ({"movie": "철의 심장"}, 1),
    "movies_by_view_grade": ({}, 4),
    "theater_counts_today": ({}, 2),
}


def test_twelve_unique_presets():
    ids = [p.preset_id for p in presets()]
    assert len(ids) == 12 and len(set(ids)) == 12
    assert set(ids) == set(CASES)


def test_contract_example_ids_exist():
    # ai.openapi.yaml GET /ai/presets 예시에 나온 id
    contract = {"today_after_time", "theater_schedule", "movie_theaters", "most_remaining_today"}
    assert contract <= {p.preset_id for p in presets()}


@pytest.mark.parametrize("preset", presets(), ids=lambda p: p.preset_id)
def test_shape(preset):
    assert preset.title and preset.description
    assert 1 <= len(preset.example) <= 300  # 계약: example maxLength 300
    assert set(preset.params) <= set(SLOT_KINDS)
    used = set(re.findall(r":([a-z_]+)", preset.sql))
    # SQL의 :이름 = params + 서버가 넣는 값(today·now)
    assert used - set(SYSTEM_PARAMS) == set(preset.params)


@pytest.mark.parametrize("preset", presets(), ids=lambda p: p.preset_id)
def test_passes_validator_and_returns_expected_rows(preset):
    slots, expected = CASES[preset.preset_id]
    result = execute(loaded_store(), validate(preset.sql), SYSTEM | slots)
    assert result.row_count == expected


def test_started_screening_is_excluded_even_if_time_is_earlier():
    """시작한 회차(18:00, 지금 19:40)는 '17시 이후'로 물어도 나오지 않는다(:now 조건)."""
    preset = next(p for p in presets() if p.preset_id == "today_after_time")
    result = execute(loaded_store(), validate(preset.sql), SYSTEM | {"time": "17:00"})
    col = result.columns.index({"name": "starts_at", "type": "text"})
    starts = [row[col] for row in result.rows]
    assert "2026-11-17 18:00" not in starts and result.row_count == 5  # 19:50부터 5건


def test_movie_examples_have_no_fixed_titles():
    """버튼 예시에 실제 편성에 없을 수 있는 고정 영화 제목을 쓰지 않는다(#92 리뷰)."""
    fixture_titles = {"별의 항해", "한강의 밤", "고양이 탐정", "철의 심장"}
    for p in presets():
        assert not any(t in p.example for t in fixture_titles), p.preset_id
        if "movie" in p.params:
            assert "{movie}" in p.example


def test_showing_movie_falls_back_when_the_query_is_interrupted():
    """실행기의 5초 중단이 같은 연결의 이 질의를 끊어도 500이 아니라 None("영화 제목")이다."""
    import sqlite3
    from contextlib import contextmanager
    from types import SimpleNamespace

    from seatsac_ai.api.presets import showing_movie

    class Conn:
        def execute(self, *_):
            raise sqlite3.OperationalError("interrupted")

    class Store:
        def is_ready(self):
            return True

        @contextmanager
        def reader(self):
            yield Conn()

    request = SimpleNamespace(
        app=SimpleNamespace(state=SimpleNamespace(snapshot=SimpleNamespace(store=Store())))
    )
    assert showing_movie(request) is None
