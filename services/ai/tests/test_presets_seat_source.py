"""프리셋은 확인되지 않은 잔여석을 숫자로 내보내지 않는다.

catalog 는 예매 서비스가 300ms 안에 답하지 않으면 remainingSeats 에 전체 좌석 수를 넣고
remainingSeatsSource 를 TOTAL 로 준다(계약 catalog.openapi.yaml). 그 값을 그대로 보여 주면
예매 서비스가 느리거나 멈춘 동안 모든 회차가 "전석 남음"으로 보인다.
sql_fixtures 의 5번 회차(오늘 22:30, 잔여 100/100, TOTAL)가 그 경우다.
"""

import re

import pytest

from seatsac_ai.resources import presets
from seatsac_ai.sql.executor import execute
from seatsac_ai.sql.validator import validate

from .sql_fixtures import SYSTEM, loaded_store

UNSURE_STARTS_AT = "2026-11-17 22:30"


def run(preset_id, slots=None):
    preset = next(p for p in presets() if p.preset_id == preset_id)
    result = execute(loaded_store(), validate(preset.sql), SYSTEM | (slots or {}))
    names = [c["name"] for c in result.columns]
    return [dict(zip(names, row, strict=True)) for row in result.rows]


@pytest.mark.parametrize(
    ("preset_id", "slots"),
    [("today_after_time", {"time": "20:00"}), ("late_night_today", {})],
)
def test_unsure_seat_count_is_blank_and_sure_ones_stay(preset_id, slots):
    rows = run(preset_id, slots)
    unsure = [r for r in rows if r["starts_at"] == UNSURE_STARTS_AT]
    sure = [r for r in rows if r["starts_at"] != UNSURE_STARTS_AT]
    assert len(unsure) == 1 and unsure[0]["remaining_seats"] is None
    assert sure and all(isinstance(r["remaining_seats"], int) for r in sure)


def test_sum_is_blank_when_any_screening_is_unsure():
    by_type = {r["screen_type"]: r["remaining_seats"] for r in run("screen_type_today")}
    assert by_type["NORMAL"] is None  # 5번 회차가 섞여 있다
    assert by_type["RECLINER"] == 4  # 3번 회차만, 확인된 값


@pytest.mark.parametrize("preset", presets(), ids=lambda p: p.preset_id)
def test_no_preset_shows_the_raw_seat_count(preset):
    """잔여석을 보여 주는 프리셋은 BOOKING 만 고르거나, 아니면 CASE 로 가린다."""
    sql = " ".join(preset.sql.split())
    if "remaining_seats" not in sql:
        return
    select = sql[: sql.index(" FROM ")]
    shown_raw = re.search(r"(?<!THEN )(?<!sum\()s\.remaining_seats(?!_source)", select)
    if shown_raw:
        where = sql[sql.index(" WHERE ") :]
        assert "s.remaining_seats_source = 'BOOKING'" in where, preset.preset_id
    else:
        assert "s.remaining_seats_source" in select, preset.preset_id
