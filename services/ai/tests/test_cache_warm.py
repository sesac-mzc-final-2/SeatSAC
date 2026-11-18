"""버튼 질문 캐시 예열(③): 미리 넣은 항목이 프리셋을 직접 부른 것과 같은 답을 내는지."""

import asyncio
from datetime import date

import pytest

from seatsac_ai.cache import template as tpl
from seatsac_ai.cache.slots import RELATIVE_DAYS
from seatsac_ai.cache.warm import WarmError, _item, preset_items, warm
from seatsac_ai.resources import presets
from seatsac_ai.sql.executor import execute
from seatsac_ai.sql.validator import validate

from .llm_fakes import ScriptedLlm
from .sql_fixtures import NOW, TODAY, loaded_store
from .test_cache_flow import ask, make

ITEMS = preset_items(presets())
VALUES = {
    "time": "20:00",
    "date": "2026-11-18",
    "theater": "건대입구",
    "movie": "철의 심장",
    "n": 2,
}
CACHE_PARAMS = tpl.system_params(date.fromisoformat(TODAY), NOW)


def test_every_cacheable_preset_has_phrasings():
    warmed = {i.entry.preset_id for i in ITEMS}
    for p in presets():
        if "grade" not in p.params:
            assert p.preset_id in warmed and len(p.phrasings) >= 2, p.preset_id
    assert "movies_by_view_grade" not in warmed  # 등급은 캐시하지 않는다


@pytest.mark.parametrize("item", ITEMS, ids=lambda i: i.template)
def test_warm_entry_answers_like_the_preset(item):
    preset = next(p for p in presets() if p.preset_id == item.entry.preset_id)
    slots = {k: VALUES[k] for k in item.entry.kinds}
    direct = dict(slots)
    if "date" in preset.params and "date" not in slots:
        offset = RELATIVE_DAYS[item.entry.relative_words[0]]
        direct["date"] = CACHE_PARAMS["today" if offset == 0 else f"d{offset}"]
    want = execute(loaded_store(), validate(preset.sql), CACHE_PARAMS | direct)
    got = execute(loaded_store(), validate(item.entry.sql), CACHE_PARAMS | slots)
    assert got.rows == want.rows


def test_phrasing_must_match_the_preset_slots():
    preset = next(p for p in presets() if p.preset_id == "theater_schedule")
    with pytest.raises(WarmError):
        _item(preset, "{theater} 편성표")  # 날짜가 없다
    with pytest.raises(WarmError):
        _item(preset, "내일 {theater} {movie} 편성표")  # 프리셋이 쓰지 않는 빈칸


def test_first_user_gets_a_cached_answer_without_claude():
    service, cache = make(ScriptedLlm())  # Claude 답이 없다 — 부르면 실패한다
    asyncio.run(warm(cache, ITEMS))
    after = ask(service, "오늘 밤 9시 넘어서 하는 영화 뭐 있어")
    assert after["path"] == "CACHE_EXACT" and after["llm"]["calls"] == 0
    assert after["rowCount"] == 3 and after["answer"].startswith("오늘 밤 9시 이후 회차: 3건")
    schedule = ask(service, "내일 건대입구 편성표 보여줘")
    assert schedule["path"] == "CACHE_EXACT" and schedule["presetId"] == "theater_schedule"
    assert schedule["rowCount"] == 2


def test_question_with_an_extra_condition_still_goes_to_claude():
    from seatsac_ai.cache.slots import canonical

    from .llm_fakes import tool
    from .test_cache_flow import FakeEmbedder

    # 유사도를 일부러 0.99로 — 새 낱말("액션") 가드가 막는지 본다(임베딩 우연에 기대지 않게)
    warm_t, new_t = canonical("오늘 {time} 이후 영화"), canonical("오늘 {time} 이후 액션 영화")
    embedder = FakeEmbedder({warm_t: [1.0, 0.0], new_t: [0.99, 0.05]})
    service, cache = make(ScriptedLlm(tool("out_of_scope", reason="x")), embedder)
    asyncio.run(warm(cache, ITEMS))
    assert ask(service, "오늘 21시 이후 액션 영화")["path"] == "OUT_OF_SCOPE"


def test_warm_stops_when_the_store_fails():
    class Broken:
        embedder = None

        def __init__(self):
            self.calls = 0

        async def semantic_available(self):
            return False

        async def put(self, *_):
            self.calls += 1
            return False

    broken = Broken()
    assert asyncio.run(warm(broken, ITEMS)) == 0 and broken.calls == 1
