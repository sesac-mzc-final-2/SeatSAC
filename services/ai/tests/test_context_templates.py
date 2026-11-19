"""문맥 이어받기 내부 SQL 틀 7개(context_templates.yaml): 모양·검증·실행·예열·밖으로 새지 않음."""

import asyncio
import re

import pytest

from seatsac_ai.cache.slots import normalize
from seatsac_ai.cache.warm import _item, internal_items, preset_items, warm
from seatsac_ai.llm import prompt
from seatsac_ai.llm.tools import tool_definitions
from seatsac_ai.resources import SYSTEM_PARAMS, context_templates, presets
from seatsac_ai.sql.executor import execute
from seatsac_ai.sql.validator import validate, with_screening_id

from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import SYSTEM, loaded_store
from .test_cache_flow import ask, make

TEMPLATES = context_templates()
IDS = {t.preset_id for t in TEMPLATES}
# 시각 14:00 = 그 회차의 시작 시각 — 「이후」(>=)·「이전」(<=) 모두 경계를 포함한다
VALUES = {"date": "2026-11-18", "theater": "건대입구", "movie": "철의 심장", "time": "14:00"}
# 틀마다 결과 열과 sql_fixtures 기준 기대 행
EXPECTED = {
    "ctx_movie_date_theater": (["starts_at"], [["2026-11-18 14:00"]]),
    "ctx_movie_date": (["theater_name", "starts_at"], [["건대입구", "2026-11-18 14:00"]]),
    # 건대입구의 철의 심장은 11/18 14:00 하나다(강남 회차 3건은 빠진다)
    "ctx_movie_theater": (["starts_at"], [["2026-11-18 14:00"]]),
    "ctx_movie_date_theater_after": (["starts_at"], [["2026-11-18 14:00"]]),
    "ctx_movie_date_theater_before": (["starts_at"], [["2026-11-18 14:00"]]),
    "ctx_movie_date_after": (["theater_name", "starts_at"], [["건대입구", "2026-11-18 14:00"]]),
    "ctx_movie_date_before": (["theater_name", "starts_at"], [["건대입구", "2026-11-18 14:00"]]),
}
# 시각 틀: 방향 → (틀에 써 둔 연산자, 회차 14:00 이 빠지는 시각)
TIME_SIDE = {"after": (">=", "14:01"), "before": ("<=", "13:59")}
# 사용자 결정(11/19): 이어받은 답의 표에 넣지 않는 열
HIDDEN = {"auditorium_name", "screen_type", "ends_at", "remaining_seats", "business_date"}


def test_templates_cover_every_combination_with_a_movie_and_one_time_bound():
    assert IDS == set(EXPECTED) and len(TEMPLATES) == 7
    # 고르는 열쇠 = (빈칸 종류, 시각 방향). 겹치지 않아야 하나로 정해진다
    keys = [(frozenset(t.params), t.time_direction) for t in TEMPLATES]
    assert len(set(keys)) == len(keys)
    assert set(keys) == {
        (frozenset({"date", "theater", "movie"}), None),
        (frozenset({"date", "movie"}), None),
        (frozenset({"theater", "movie"}), None),
        (frozenset({"date", "theater", "movie", "time"}), "after"),
        (frozenset({"date", "theater", "movie", "time"}), "before"),
        (frozenset({"date", "movie", "time"}), "after"),
        (frozenset({"date", "movie", "time"}), "before"),
    }


TIMED = [t for t in TEMPLATES if t.time_direction is not None]


@pytest.mark.parametrize("template", TIMED, ids=lambda t: t.preset_id)
def test_time_template_has_its_operator_written_in_and_binds_the_values(template):
    """연산자는 틀에 미리 써 둔 것이고 값은 바인딩이다 — SQL에 글자를 끼워 넣지 않는다."""
    operator, outside = TIME_SIDE[template.time_direction]
    # 시각은 날짜에 붙는다(극장+영화 틀에는 없다). 프리셋 today_after_time 과 같은 비교 모양
    assert "time" in template.params and "date" in template.params
    assert template.sql.count(f"s.starts_at {operator} :date || ' ' || :time") == 1
    assert len(re.findall(r"s\.starts_at\s*[<>]=?", template.sql)) == 2  # 시각 하나 + 지금 이후
    assert "s.starts_at > :now" in template.sql and "s.business_date = :date" in template.sql
    slots = {k: VALUES[k] for k in template.params} | {"time": outside}
    assert execute(loaded_store(), validate(template.sql), SYSTEM | slots).rows == []


def test_templates_without_a_time_have_no_time_condition():
    for template in TEMPLATES:
        if template.time_direction is None:
            assert "time" not in template.params and ":time" not in template.sql


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
def test_one_phrasing_with_every_blank(template):
    (phrasing,) = template.phrasings  # 세션에 남기는 완성 문장으로 쓴다
    assert set(re.findall(r"\{([a-z]+)\}", phrasing)) == set(template.params)


def test_warm_items_use_their_own_keys_and_stay_out_of_the_semantic_index():
    """내부 틀 항목의 키는 질문 말투가 아니다 — 직접 친 질문의 캐시 조회에 걸리지 않는다."""
    items = internal_items(TEMPLATES)
    assert [i.key_text for i in items] == [f"ctx {t.preset_id}" for t in TEMPLATES]
    assert all(not i.semantic for i in items)
    # 질문의 키(normalize)는 빈칸을 모두 지운다 → 빈칸이 든 이 키와는 같아질 수 없다
    for item in items:
        assert " " in item.key_text and normalize(item.key_text) != item.key_text
    preset_keys = {i.key_text for i in preset_items(presets())}
    assert not preset_keys & {i.key_text for i in items}
    for item in items:
        template = next(t for t in TEMPLATES if t.preset_id == item.entry.preset_id)
        assert item.entry.sql == template.sql


def test_warming_adds_nothing_to_the_semantic_index_for_the_templates():
    """의미 색인에는 프리셋 말투만 들어간다 — 내부 틀이 프리셋 적중을 가리지 않는다."""
    _, only_presets = make(ScriptedLlm())
    asyncio.run(warm(only_presets, preset_items(presets())))
    _, both = make(ScriptedLlm())
    stored = asyncio.run(warm(both, preset_items(presets()) + internal_items(TEMPLATES)))
    assert stored == len(preset_items(presets())) + len(TEMPLATES)
    assert both._index.docs.keys() == only_presets._index.docs.keys()
    assert not any("ctx_" in entry for _, _, entry in both._index.docs.values())


def test_templates_are_not_presets():
    """화면 버튼·Claude 프롬프트·도구 enum 은 presets() 에서만 나온다 — 내부 틀이 섞이지 않는다."""
    assert len(presets()) == 13 and not IDS & {p.preset_id for p in presets()}
    enum = tool_definitions()[0]["input_schema"]["properties"]["presetId"]["enum"]
    assert len(enum) == 13 and not IDS & set(enum)
    assert "ctx_" not in prompt.system_text()


def test_presets_api_does_not_list_the_templates(client, user_headers):
    items = client.get("/ai/presets", headers=user_headers).json()["items"]
    assert len(items) == 13 and not IDS & {i["presetId"] for i in items}


class SameVector:
    """모든 글을 같은 벡터로 — 의미 색인에 있기만 하면 유사도 1.0 으로 최근접이 된다."""

    def embed(self, text):
        return [1.0, 0.0, 0.0]


TYPED_LIKE_A_TEMPLATE = [
    "오늘 건대입구 철의 심장 시간표 알려줘",  # 리뷰에서 정확 일치로 내부 틀에 적중했던 문장
    "철의 심장 건대입구 시간표",  # 의미 캐시 0.996 으로 적중했던 문장
    "11월 18일 건대입구 철의 심장 회차",  # 내부 틀의 완성 문장 그대로
    "내일 건대입구 철의 심장 회차",
    "건대입구 철의 심장 회차",
    "내일 철의 심장 회차",
]


@pytest.mark.parametrize("question", TYPED_LIKE_A_TEMPLATE)
def test_typed_question_never_hits_a_template_even_when_warmed(question):
    """직접 친 질문은 내부 틀로 답하지 않는다 — 예열된 상태에서도 Claude로 간다(main 과 같다)."""
    llm = ScriptedLlm(tool("out_of_scope", reason="x"))  # Claude까지 갔는지만 본다
    service, cache = make(llm, SameVector())
    asyncio.run(warm(cache, preset_items(presets()) + internal_items(TEMPLATES)))
    body = ask(service, question)
    assert body["path"] == "OUT_OF_SCOPE" and body["llm"]["calls"] == 1 and len(llm.requests) == 1


def test_template_entry_found_under_a_question_key_is_treated_as_a_miss():
    """방어: 질문 말투의 키에 내부 틀 항목이 있어도(첫 판의 예열) 직접 친 질문에 쓰지 않는다."""
    llm = ScriptedLlm(tool("out_of_scope", reason="x"), tool("out_of_scope", reason="x"))
    service, cache = make(llm, SameVector())
    old = _item(TEMPLATES[0], "내일 {theater} {movie} 회차")
    asyncio.run(cache.put(old.key_text, old.entry, [1.0, 0.0, 0.0]))  # 정확 일치 + 의미 색인 둘 다
    exact = ask(service, "내일 건대입구 철의 심장 회차")
    assert exact["path"] == "OUT_OF_SCOPE" and exact["presetId"] is None
    similar = ask(service, "내일 건대입구 철의 심장 회차 알려줄 수 있어")  # 의미 캐시로만 닿는 문장
    assert similar["path"] == "OUT_OF_SCOPE" and len(llm.requests) == 2


def test_preset_button_questions_are_answered_as_before_when_templates_are_warmed():
    service, cache = make(ScriptedLlm())  # Claude 답이 없다 — 부르면 실패한다
    asyncio.run(warm(cache, preset_items(presets()) + internal_items(TEMPLATES)))
    schedule = ask(service, "내일 건대입구 편성표 보여줘")
    assert schedule["path"] == "CACHE_EXACT" and schedule["presetId"] == "theater_schedule"
    movie = ask(service, "철의 심장 어디서 몇 시에 해?")
    assert movie["presetId"] == "movie_theaters" and movie["rowCount"] == 4
    after = ask(service, "오늘 밤 9시 넘어서 하는 영화 뭐 있어")
    assert after["path"] == "CACHE_EXACT" and after["presetId"] == "today_after_time"
