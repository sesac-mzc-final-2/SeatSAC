"""문맥 이어받기 내부 SQL 틀 3개(context_templates.yaml): 모양·검증·실행·예열·밖으로 새지 않음."""

import asyncio
import re
from datetime import date

import pytest

from seatsac_ai.cache import template as tpl
from seatsac_ai.cache.warm import preset_items, warm
from seatsac_ai.llm import prompt
from seatsac_ai.llm.tools import tool_definitions
from seatsac_ai.resources import SYSTEM_PARAMS, context_templates, presets
from seatsac_ai.sql.executor import execute
from seatsac_ai.sql.validator import validate, with_screening_id

from .llm_fakes import ScriptedLlm
from .sql_fixtures import NOW, SYSTEM, TODAY, loaded_store
from .test_cache_flow import ask, make

TEMPLATES = context_templates()
IDS = {t.preset_id for t in TEMPLATES}
VALUES = {"date": "2026-11-18", "theater": "건대입구", "movie": "철의 심장"}
# 틀마다 결과 열과 sql_fixtures 기준 기대 행
EXPECTED = {
    "ctx_movie_date_theater": (["starts_at"], [["2026-11-18 14:00"]]),
    "ctx_movie_date": (["theater_name", "starts_at"], [["건대입구", "2026-11-18 14:00"]]),
    # 건대입구의 철의 심장은 11/18 14:00 하나다(강남 회차 3건은 빠진다)
    "ctx_movie_theater": (["starts_at"], [["2026-11-18 14:00"]]),
}
# 사용자 결정(11/19): 이어받은 답의 표에 넣지 않는 열
HIDDEN = {"auditorium_name", "screen_type", "ends_at", "remaining_seats", "business_date"}


def test_three_templates_cover_every_combination_with_a_movie():
    assert IDS == set(EXPECTED)
    assert {frozenset(t.params) for t in TEMPLATES} == {
        frozenset({"date", "theater", "movie"}),
        frozenset({"date", "movie"}),
        frozenset({"theater", "movie"}),
    }


@pytest.mark.parametrize("template", TEMPLATES, ids=lambda t: t.preset_id)
def test_passes_validator_and_returns_expected_rows(template):
    used = set(re.findall(r":([a-z_]+)", template.sql))
    assert used - set(SYSTEM_PARAMS) == set(template.params)
    slots = {k: VALUES[k] for k in template.params}
    result = execute(loaded_store(), validate(template.sql), SYSTEM | slots)
    columns, rows = EXPECTED[template.preset_id]
    assert [c["name"] for c in result.columns] == columns and result.rows == rows
    assert not HIDDEN & set(columns)


@pytest.mark.parametrize("template", TEMPLATES, ids=lambda t: t.preset_id)
def test_rows_can_carry_screening_ids(template):
    """바깥 SELECT 하나 · screening 한 번 · s.starts_at 그대로 → 좌석 화면 링크가 붙는다(#150)."""
    assert with_screening_id(validate(template.sql)) is not None


@pytest.mark.parametrize("template", TEMPLATES, ids=lambda t: t.preset_id)
def test_first_phrasing_has_every_blank_and_title_has_no_date(template):
    blanks = set(re.findall(r"\{([a-z]+)\}", template.phrasings[0]))
    assert blanks == set(template.params)  # 이어받은 답의 캐시 키·완성 문장으로 쓴다
    assert "{date}" not in template.title  # "내일" 말투에는 채울 날짜 글자가 없다


def test_phrasings_do_not_collide_with_the_presets():
    """기동 때 예열(main.lifespan)과 같은 호출 — 말투의 캐시 키가 겹치면 WarmError 다."""
    items = preset_items(presets() + context_templates())
    only_presets = preset_items(presets())
    mine = [i for i in items if i.entry.preset_id in IDS]
    assert len(items) == len(only_presets) + len(mine)
    assert len(mine) == sum(len(t.phrasings) for t in TEMPLATES)  # 틀끼리도 겹치지 않는다
    # 프리셋 말투의 항목은 틀을 같이 넣어도 그대로다
    assert [i for i in items if i.entry.preset_id not in IDS] == only_presets


def test_warmed_entries_answer_like_the_template():
    params = tpl.system_params(date.fromisoformat(TODAY), NOW)
    for item in (i for i in preset_items(context_templates()) if "내일" in i.template):
        template = next(t for t in TEMPLATES if t.preset_id == item.entry.preset_id)
        slots = {k: VALUES[k] for k in item.entry.kinds}
        got = execute(loaded_store(), validate(item.entry.sql), params | slots)
        assert got.rows == EXPECTED[template.preset_id][1]  # 내일 = 11/18


def test_templates_are_not_presets():
    """화면 버튼·Claude 프롬프트·도구 enum 은 presets() 에서만 나온다 — 내부 틀이 섞이지 않는다."""
    assert len(presets()) == 13 and not IDS & {p.preset_id for p in presets()}
    enum = tool_definitions()[0]["input_schema"]["properties"]["presetId"]["enum"]
    assert len(enum) == 13 and not IDS & set(enum)
    assert "ctx_" not in prompt.system_text()


def test_presets_api_does_not_list_the_templates(client, user_headers):
    items = client.get("/ai/presets", headers=user_headers).json()["items"]
    assert len(items) == 13 and not IDS & {i["presetId"] for i in items}


def test_typed_question_in_a_template_phrasing_is_answered_from_the_warmed_cache():
    service, cache = make(ScriptedLlm())  # Claude 답이 없다 — 부르면 실패한다
    asyncio.run(warm(cache, preset_items(presets() + context_templates())))
    body = ask(service, "내일 건대입구 철의 심장 회차")
    assert body["path"] == "CACHE_EXACT" and body["llm"]["calls"] == 0
    assert body["presetId"] == "ctx_movie_date_theater"
    assert body["rows"] == [["2026-11-18 14:00"]] and body["screeningIds"] == [7]
    assert body["answer"].startswith("건대입구 철의 심장 회차: 1건입니다.")
    # 기존 버튼 질문은 예전과 같은 프리셋으로 답한다
    schedule = ask(service, "내일 건대입구 편성표 보여줘")
    assert schedule["presetId"] == "theater_schedule" and schedule["rowCount"] == 2
    movie = ask(service, "철의 심장 어디서 몇 시에 해?")
    assert movie["presetId"] == "movie_theaters" and movie["rowCount"] == 4
