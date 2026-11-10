"""schema.yaml(Claude용 설명서)이 실제 스냅샷 표와 어긋나지 않는지 확인한다."""

import pytest

from seatsac_ai.resources import schema_doc
from seatsac_ai.snapshot import schema
from seatsac_ai.sql.executor import execute
from seatsac_ai.sql.validator import validate

from .sql_fixtures import loaded_store

ACTUAL = {
    "movie": schema.MOVIE_COLUMNS,
    "theater": schema.THEATER_COLUMNS,
    "screening": schema.SCREENING_COLUMNS,
}


def test_describes_exactly_the_queryable_tables():
    assert set(schema_doc()["tables"]) == set(schema.QUERYABLE_TABLES)


@pytest.mark.parametrize("table", schema.QUERYABLE_TABLES)
def test_describes_every_column_and_nothing_else(table):
    described = schema_doc()["tables"][table]["columns"]
    assert list(described) == list(ACTUAL[table])
    assert all(isinstance(text, str) and text.strip() for text in described.values())


def test_enum_values_match_catalog_contract():
    enums = schema_doc()["enums"]
    assert set(enums["view_grade"]) == {"ALL", "12", "15", "19"}
    assert set(enums["screen_type"]) == {"NORMAL", "RECLINER"}
    assert set(enums["remaining_seats_source"]) == {"BOOKING", "TOTAL"}


def test_has_five_examples():
    assert len(schema_doc()["examples"]) == 5


@pytest.mark.parametrize("example", schema_doc()["examples"], ids=lambda e: e["question"])
def test_examples_pass_validator_and_run(example):
    v = validate(example["sql"])
    if "screening" in v.tables:
        assert "starts_at >" in example["sql"], "회차 조회에는 starts_at > 지금 이 있어야 한다"
    execute(loaded_store(), v)  # 오류 없이 실행된다
