"""스스로 배우기(3.4.23): Claude가 버튼을 골라 답한 새 말투는 캐시 수명이 지나도 캐시로 답한다."""

import asyncio
import contextlib
import dataclasses
import json
from datetime import timedelta

import fakeredis
import pytest
from redis.exceptions import ConnectionError as RedisConnectionError

from seatsac_ai.cache import learned as learned_mod
from seatsac_ai.cache.learned import LEARN_RESULTS, LEARNED_KEY, LEARNED_TTL, LearnedBook
from seatsac_ai.cache.slots import normalize
from seatsac_ai.cache.store import ChatCache, MemoryIndex
from seatsac_ai.cache.warm import (
    INTERNAL_PRESET_PREFIX,
    internal_items,
    preset_items,
    refresh_forever,
    rename_date,
    warm,
)
from seatsac_ai.common import metrics
from seatsac_ai.config import Settings
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.main import create_app
from seatsac_ai.resources import context_templates, presets

from . import test_context_carry as carry
from . import test_seat_hold as seat
from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store
from .test_cache_flow import AFTER_20_SQL, NOW, FakeEmbedder, ask, preset_after

NEW_PHRASING = "오늘 밤 8시 이후 뭐 상영해"  # "상영해"가 버튼 말투에 없는 낱말


def make(llm, valkey=None):
    valkey = valkey or fakeredis.FakeAsyncRedis(decode_responses=True)
    index = MemoryIndex()
    cache = ChatCache(
        valkey, ttl=3600, session_ttl=1800, threshold=0.92, embedder=FakeEmbedder(), index=index
    )
    asyncio.run(cache.start())
    book = LearnedBook(valkey, presets())
    service = ChatService(
        loaded_store(),
        llm,
        Settings(_env_file=None),
        clock=lambda: NOW,
        cache=cache,
        learned=book,
    )
    return service, cache, book, valkey, index


def expire_cache(valkey, index):
    """캐시 수명(1시간)이 지난 상태로: 정확 일치 키와 의미 캐시 문서를 지운다. 장부는 남는다."""

    async def run():
        for key in await valkey.keys("ai:exact:*"):
            await valkey.delete(key)

    asyncio.run(run())
    index.docs.clear()


def lines(valkey):
    return asyncio.run(valkey.hgetall(LEARNED_KEY))


def counted(result):
    return metrics.AI_CACHE_LEARNED.labels(result)._value.get()


def test_phrasing_answered_with_a_preset_survives_cache_expiry():
    llm = ScriptedLlm(preset_after("20:00"))  # Claude 답은 한 번뿐 — 또 부르면 실패한다
    service, cache, book, valkey, index = make(llm)
    before = counted("stored")
    first = ask(service, NEW_PHRASING)
    assert first["path"] == "PRESET" and first["llm"]["calls"] == 1
    assert len(lines(valkey)) == 1 and counted("stored") == before + 1

    expire_cache(valkey, index)
    items = asyncio.run(book.items())
    assert [i.entry.preset_id for i in items] == ["today_after_time"]
    assert asyncio.run(warm(cache, items)) == 1  # 예열 루프가 하는 일

    again = ask(service, NEW_PHRASING)
    assert again["path"] == "CACHE_EXACT" and again["llm"]["calls"] == 0
    assert again["rows"] == first["rows"] and again["presetId"] == "today_after_time"
    other_time = ask(service, "오늘 밤 9시 이후 뭐 상영해")  # 값만 다른 같은 말투
    assert other_time["path"] == "CACHE_EXACT" and other_time["rowCount"] == 3
    assert len(llm.requests) == 1


def test_sql_written_by_claude_is_not_learned():
    reply = tool("run_sql", sql=AFTER_20_SQL, explanation="오늘 20시 이후 회차")
    service, _, _, valkey, _ = make(ScriptedLlm(reply))
    assert ask(service, NEW_PHRASING)["path"] == "NL2SQL"
    assert lines(valkey) == {}


def test_button_phrasing_is_left_to_the_warm_loop():
    service, _, _, valkey, _ = make(ScriptedLlm(preset_after("20:00")))
    before = counted("skipped")
    assert ask(service, "오늘 20시 이후 영화 알려줘")["path"] == "PRESET"
    assert lines(valkey) == {} and counted("skipped") == before + 1


def other_phrasing(item, template="오늘 {time} 이후 틀어 주는 영화"):
    """같은 버튼의 다른 말투(키·템플릿이 서로 맞는 항목)."""
    return normalize(template), dataclasses.replace(item.entry, template=template)


def test_learning_twice_keeps_one_line_and_the_key_has_a_ttl():
    service, _, book, valkey, _ = make(ScriptedLlm(preset_after("20:00")))
    ask(service, NEW_PHRASING)
    item = asyncio.run(book.items())[0]
    assert asyncio.run(book.learn(item.key_text, item.entry)) == "known"
    assert len(lines(valkey)) == 1
    assert LEARNED_TTL - 5 <= asyncio.run(valkey.ttl(LEARNED_KEY)) <= LEARNED_TTL


def test_each_line_ages_out_seven_days_after_it_was_learned():
    """키 전체 TTL은 새 줄이 적힐 때마다 늘어난다 — 줄마다 배운 때를 보고 지워야 7일이 지켜진다."""
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    clock = [1_000_000.0]
    service, _, _, _, _ = make(ScriptedLlm(preset_after("20:00")), valkey)
    ask(service, NEW_PHRASING)  # 이 장부는 실제 시계로 적는다 — 아래 장부로 옮겨 적는다
    seed = asyncio.run(LearnedBook(valkey, presets()).items())[0]
    asyncio.run(valkey.delete(LEARNED_KEY))

    book = LearnedBook(valkey, presets(), clock=lambda: clock[0])
    assert asyncio.run(book.learn(seed.key_text, seed.entry)) == "stored"
    clock[0] += LEARNED_TTL - 60
    assert asyncio.run(book.learn(*other_phrasing(seed))) == "stored"  # 6일 23시간 뒤 다른 말투
    assert len(asyncio.run(book.items())) == 2
    clock[0] += 120  # 첫 줄만 7일을 넘겼다
    assert [i.key_text for i in asyncio.run(book.items())] == [other_phrasing(seed)[0]]
    assert len(lines(valkey)) == 1


def test_same_phrasing_with_a_different_answer_is_unlearned():
    service, _, book, valkey, _ = make(ScriptedLlm(preset_after("20:00")))
    ask(service, NEW_PHRASING)
    item = asyncio.run(book.items())[0]
    # 같은 말투에 Claude가 이번엔 다른 버튼을 골랐다(빈칸 종류가 같은 버튼)
    late = next(p for p in presets() if p.preset_id == "late_night_today")
    other = dataclasses.replace(
        item.entry, preset_id=late.preset_id, sql=rename_date(late.sql, "date"), kinds=()
    )
    if not book.accepts(item.key_text, other):
        pytest.skip("이 버튼 조합으로는 조건을 만들 수 없다")
    assert asyncio.run(book.learn(item.key_text, other)) == "dropped"
    assert lines(valkey) == {}


def test_phrasing_without_a_day_word_is_not_pinned_to_today():
    """ "주말 상영작 목록"에 Claude가 오늘 날짜를 넣어 답해도 적지 않는다(다른 날엔 틀린 답)."""
    today = NOW.date().isoformat()
    reply = tool("run_preset", presetId="movies_on_date", params={"date": today})
    service, _, _, valkey, _ = make(ScriptedLlm(reply))
    before = counted("skipped")
    assert ask(service, "주말 상영작 목록")["path"] == "PRESET"
    assert lines(valkey) == {} and counted("skipped") == before + 1


@pytest.mark.parametrize(
    "question",
    [
        "주말 밤 8시 이후 뭐 상영해",
        "어제 밤 8시 이후 뭐 상영했어",
        "다음 주 밤 8시 이후 상영 일정",
        "크리스마스 밤 8시 이후 상영 일정",
    ],
)
def test_day_words_the_slots_cannot_read_are_not_learned_as_today(question):
    """값으로 뽑지 못한 날짜 말("주말")이 있으면, Claude가 오늘 버튼을 골라도 배우지 않는다."""
    service, _, _, valkey, _ = make(ScriptedLlm(preset_after("20:00")))
    before = counted("skipped")
    assert ask(service, question)["path"] == "PRESET"
    assert lines(valkey) == {} and counted("skipped") == before + 1


def test_unexpected_error_while_learning_does_not_break_the_answer():
    service, _, _, valkey, _ = make(ScriptedLlm(preset_after("20:00")))

    async def boom(*_a, **_k):
        raise RuntimeError("Valkey 오류가 아닌 실패")

    valkey.hlen = boom
    before = counted("error")
    assert ask(service, NEW_PHRASING)["path"] == "PRESET"
    assert counted("error") == before + 1


def test_phrasing_with_a_day_word_is_learned_and_follows_the_calendar():
    tomorrow = (NOW + timedelta(days=1)).date().isoformat()
    reply = tool("run_preset", presetId="movies_on_date", params={"date": tomorrow})
    llm = ScriptedLlm(reply)
    service, cache, book, valkey, index = make(llm)
    first = ask(service, "내일 뭐뭐 틀어")
    assert first["path"] == "PRESET" and len(lines(valkey)) == 1
    expire_cache(valkey, index)
    asyncio.run(warm(cache, asyncio.run(book.items())))
    next_day = ask(service, "내일 뭐뭐 틀어", now=NOW + timedelta(days=1))
    assert next_day["path"] == "CACHE_EXACT" and len(llm.requests) == 1
    day_after = (NOW + timedelta(days=2)).date().isoformat()
    assert f"business_date = '{day_after}'" in next_day["sql"]  # 그날 기준의 "내일"


def test_full_book_and_long_sentences_are_not_stored(monkeypatch):
    service, _, book, valkey, _ = make(ScriptedLlm(preset_after("20:00")))
    ask(service, NEW_PHRASING)
    item = asyncio.run(book.items())[0]
    long_template = "오늘 {time} 이후 " + "가" * learned_mod.TEMPLATE_MAX
    assert asyncio.run(book.learn(*other_phrasing(item, long_template))) == "skipped"
    monkeypatch.setattr(learned_mod, "LEARNED_MAX", 1)
    assert asyncio.run(book.learn(*other_phrasing(item))) == "full"
    assert len(lines(valkey)) == 1


def test_lines_that_no_longer_match_are_dropped_and_do_not_stop_the_rest():
    service, _, book, valkey, _ = make(ScriptedLlm(preset_after("20:00")))
    ask(service, NEW_PHRASING)
    item = asyncio.run(book.items())[0]

    def line(entry, key_text=item.key_text):
        return json.dumps({"keyText": key_text, "entry": entry.to_json(), "learnedAt": 2**40})

    async def plant():
        changed = dataclasses.replace(item.entry, sql="SELECT title FROM movie")
        gone = dataclasses.replace(item.entry, preset_id="removed_preset")
        await valkey.hset(LEARNED_KEY, "sql-changed", line(changed))
        await valkey.hset(LEARNED_KEY, "preset-gone", line(gone))
        await valkey.hset(LEARNED_KEY, "key-from-old-rules", line(item.entry, "옛규칙키"))
        await valkey.hset(LEARNED_KEY, "broken", "{not json")
        bad = json.loads(line(item.entry))
        bad["entry"] = json.dumps({**json.loads(bad["entry"]), "sql": ["목록"]})
        await valkey.hset(LEARNED_KEY, "wrong-type", json.dumps(bad))

    asyncio.run(plant())
    assert len(asyncio.run(book.items())) == 1
    assert len(lines(valkey)) == 1


def test_valkey_failure_does_not_break_the_answer():
    service, _, book, valkey, _ = make(ScriptedLlm(preset_after("20:00")))

    async def boom(*_a, **_k):
        raise RedisConnectionError("down")

    valkey.hget = boom
    valkey.hgetall = boom
    before = counted("error")
    assert ask(service, NEW_PHRASING)["path"] == "PRESET"
    assert counted("error") == before + 1
    assert asyncio.run(book.items()) == []


def test_broken_book_does_not_stop_the_preset_warm():
    service, cache, _, _, _ = make(ScriptedLlm())

    class Broken:
        async def items(self):
            raise TypeError("읽다가 깨짐")

    async def one_round():
        loop = refresh_forever(cache, preset_items(presets()), 3600, Broken())
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(loop, 1)

    asyncio.run(one_round())
    assert ask(service, "오늘 20시 이후 영화 알려줘")["path"] == "CACHE_EXACT"


def all_counts():
    return {r: counted(r) for r in LEARN_RESULTS}


def test_answer_continued_from_an_earlier_answer_is_never_learned():
    """이어받은 답은 한 사용자의 앞 조건이 섞인 답이다 — 모두가 쓰는 장부에 적지 않는다."""
    service, _, _, valkey, _ = make(ScriptedLlm(carry.tomorrow_kondae()))
    first = carry.ask(service, "건대입구 내일 영화 뭐해?")
    before = all_counts()
    picked = carry.pick(service, first)  # 답 아래 영화 버튼 — 버튼(내부 틀)으로 답했다
    assert picked["context"]["inherited"] == ["date", "theater"]
    assert picked["presetId"].startswith(INTERNAL_PRESET_PREFIX) and picked["llm"]["calls"] == 0
    assert lines(valkey) == {} and all_counts() == before  # 적지도, 적으려 하지도 않았다


def test_internal_context_templates_are_refused_by_the_book():
    """장부가 내부 틀을 버튼으로 알게 되더라도(조립 실수) 그 항목은 받지 않는다."""
    templates = context_templates()
    book = LearnedBook(fakeredis.FakeAsyncRedis(decode_responses=True), presets() + templates)
    for item in internal_items(templates):
        assert asyncio.run(book.learn("새말투", item.entry)) == "skipped"
        assert asyncio.run(book.learn(item.key_text, item.entry)) == "skipped"


def test_seat_hold_answers_are_never_learned():
    service, _, valkey = seat.make(ScriptedLlm(seat.hold_tool()))
    service._learned = LearnedBook(valkey, presets())
    before = all_counts()
    first = seat.confirmed(service)
    assert seat.press(service, first)["path"] == "HOLD_RESULT"
    assert lines(valkey) == {} and all_counts() == before


def test_refresh_loop_puts_learned_phrasings_back():
    service, cache, book, valkey, index = make(ScriptedLlm(preset_after("20:00")))
    ask(service, NEW_PHRASING)
    expire_cache(valkey, index)

    async def one_round():
        loop = refresh_forever(cache, preset_items(presets()), 3600, book)
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(loop, 1)  # 첫 회차를 마치고 잠든 루프를 끊는다

    asyncio.run(one_round())
    assert ask(service, NEW_PHRASING)["path"] == "CACHE_EXACT"


def test_metric_labels_match_and_start_at_zero():
    assert metrics.LEARN_RESULTS == LEARN_RESULTS
    metrics.init_label_series()
    samples = metrics.AI_CACHE_LEARNED.collect()[0].samples
    assert {s.labels["result"] for s in samples} == set(LEARN_RESULTS)


@pytest.mark.parametrize("enabled", [True, False])
def test_flag_turns_learning_off(enabled):
    app = create_app(
        Settings(_env_file=None, APP_AI_CACHE_LEARN_ENABLED=enabled),
        valkey=fakeredis.FakeAsyncRedis(decode_responses=True),
        embedder=FakeEmbedder(),
        semantic_index=MemoryIndex(),
    )
    assert (app.state.learned is not None) is enabled
