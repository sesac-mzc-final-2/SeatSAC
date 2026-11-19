"""응답 cache 칸 (3.4.20) — 저장한 답과 그 캐시 항목에서 나온 답을 화면이 짝지을 수 있나.

데모 화면이 「①보다 토큰 N 절약」을 실제 뺄셈으로 보여 주려면 ②가 어느 답 덕분인지 알아야 한다.
캐시 항목의 모양·키·세션·로그는 그대로다(칸은 응답에만 있다).
"""

import asyncio
import json
import logging
import re

import fakeredis

from seatsac_ai.cache.slots import normalize
from seatsac_ai.cache.store import ChatCache, MemoryIndex, entry_id, exact_key
from seatsac_ai.cache.warm import preset_items, warm
from seatsac_ai.resources import presets

from .llm_fakes import ScriptedLlm, tool
from .test_cache_flow import (
    AFTER_20_SQL,
    FIRST,
    A,
    B,
    BrokenValkey,
    FakeEmbedder,
    ask,
    make,
    preset_after,
)
from .test_my_bookings import ask as ask_mine
from .test_my_bookings import bookings_api
from .test_my_bookings import make as make_mine
from .test_orchestrator import CONTRACT_KEYS

ENTRY_ID = re.compile(r"^[0-9a-f]{12}$")
QUESTION = "오늘 20시 이후 영화 알려줘"


# --- 짝: 저장한 답 ↔ 그 항목에서 나온 답 ---------------------------------------


def test_stored_answer_and_exact_hit_share_the_entry_id():
    service, _ = make(ScriptedLlm(preset_after("20:00")))
    first = ask(service, QUESTION)
    second = ask(service, QUESTION)
    assert first["path"] == "PRESET" and second["path"] == "CACHE_EXACT"
    assert ENTRY_ID.match(first["cache"]["entryId"])
    assert first["cache"] == {
        "entryId": first["cache"]["entryId"], "stored": True, "similarity": None,
    }  # fmt: skip
    assert second["cache"] == {
        "entryId": first["cache"]["entryId"], "stored": False, "similarity": None,
    }  # fmt: skip


def test_other_slot_value_is_still_the_same_entry():
    """캐시 항목은 틀(시각 빈칸)이다 — 20시로 저장하고 21시로 물어도 같은 항목."""
    llm = ScriptedLlm(tool("run_sql", sql=AFTER_20_SQL, explanation="오늘 20시 이후 회차"))
    service, _ = make(llm)
    first = ask(service, "오늘 20시 넘어서 시작하는 영화 제목")
    hit = ask(service, "오늘 21시 넘어서 시작하는 영화 제목")
    assert first["path"] == "NL2SQL" and first["cache"]["stored"] is True
    assert hit["path"] == "CACHE_EXACT" and hit["cache"]["stored"] is False
    assert hit["cache"]["entryId"] == first["cache"]["entryId"]


def test_semantic_hit_points_at_the_stored_entry_with_similarity():
    embedder = FakeEmbedder({A: [1.0, 0.0, 0.0], B: [0.97, 0.2, 0.0]})  # 코사인 0.9794…
    service, _ = make(ScriptedLlm(preset_after("20:00")), embedder)
    first = ask(service, FIRST)
    hit = ask(service, "오늘 21시 넘어서 하는 영화 보여줘")
    assert hit["path"] == "CACHE_SEMANTIC"
    # 새 질문의 키가 아니라 찾은 항목의 키 — 그래야 처음 답과 짝이 된다
    assert hit["cache"] == {
        "entryId": first["cache"]["entryId"], "stored": False, "similarity": 0.979,
    }  # fmt: skip
    assert first["cache"]["stored"] is True and first["cache"]["similarity"] is None


def test_entry_id_is_the_prefix_of_the_cache_key_hash():
    """정확 일치 키(sha256 전체)·의미 캐시 문서 ID(앞 24자)와 같은 해시의 앞 12자."""
    service, cache = make(ScriptedLlm(preset_after("20:00")), FakeEmbedder())
    body = ask(service, QUESTION)
    eid = body["cache"]["entryId"]
    keys = asyncio.run(cache._v.keys("ai:exact:*"))
    assert len(keys) == 1 and keys[0].rsplit(":", 1)[1].startswith(eid)
    assert [doc_id[:12] for doc_id in cache._index.docs] == [eid]


def test_warmed_button_question_hit_carries_its_entry_id():
    items = preset_items(presets())
    service, cache = make(ScriptedLlm())  # Claude 답이 없다 — 부르면 실패한다
    asyncio.run(warm(cache, items))
    hit = ask(service, "오늘 밤 9시 넘어서 하는 영화 뭐 있어")
    assert hit["path"] == "CACHE_EXACT" and hit["llm"]["calls"] == 0
    assert hit["cache"]["stored"] is False and hit["cache"]["similarity"] is None
    assert hit["cache"]["entryId"] in {entry_id(item.key_text) for item in items}


def test_warmed_entry_semantic_hit_points_at_the_warmed_entry():
    """예열 항목은 의미 캐시에도 들어간다 — 정확 일치 키가 사라져도 같은 항목을 가리킨다."""
    items = preset_items(presets())
    warmed = next(i for i in items if i.template == B)  # "오늘 {time} 이후 영화"
    service, cache = make(ScriptedLlm(), FakeEmbedder({B: [1.0, 0.0, 0.0]}))
    asyncio.run(warm(cache, [warmed]))
    asyncio.run(cache._v.delete(exact_key(warmed.key_text)))  # 정확 일치로는 못 찾게
    hit = ask(service, "오늘 21시 넘어서 하는 영화 보여줘")
    assert hit["path"] == "CACHE_SEMANTIC"
    assert hit["cache"] == {
        "entryId": entry_id(warmed.key_text), "stored": False, "similarity": 1.0,
    }  # fmt: skip


# --- 저장하지 않은 답은 null ---------------------------------------------------


def test_failed_save_is_null():
    service, _ = make(ScriptedLlm(preset_after("20:00")), valkey=BrokenValkey())
    body = ask(service, QUESTION)
    assert body["path"] == "PRESET" and body["cache"] is None


def test_answer_that_cannot_be_cached_is_null():
    by_id = (
        "SELECT s.starts_at FROM screening s WHERE s.theater_id = 20 "
        "AND s.starts_at > '2026-11-17 19:40'"
    )
    service, _ = make(ScriptedLlm(tool("run_sql", sql=by_id, explanation="강남 회차")))
    body = ask(service, "강남 회차")
    assert body["path"] == "NL2SQL" and body["cache"] is None


def test_followup_is_null():
    llm = ScriptedLlm(preset_after("20:00"), preset_after("20:00"))
    service, _ = make(llm)
    first = ask(service, QUESTION)
    follow = ask(service, "그중 20시 이후만", session_id=first["sessionId"])
    assert follow["path"] == "PRESET" and follow["cache"] is None


def test_out_of_scope_rejected_and_clarify_are_null():
    bad = tool("run_sql", sql="SELECT * FROM snapshot_meta", explanation="x")
    for replies, question, path in (
        ((tool("out_of_scope", reason="x"),), "내일 날씨", "OUT_OF_SCOPE"),
        ((bad, bad, bad), "오늘 20시 이후 영화 알려줘", "REJECTED"),
        ((tool("ask_user", missing="date"),), "잔여석 많은 회차", "CLARIFY"),
    ):
        service, _ = make(ScriptedLlm(*replies))
        body = ask(service, question)
        assert body["path"] == path and body["cache"] is None


def test_personal_question_is_null():
    cache = ChatCache(fakeredis.FakeAsyncRedis(decode_responses=True), ttl=3600, session_ttl=1800,
                      threshold=0.92, index=MemoryIndex())  # fmt: skip
    body = ask_mine(make_mine(bookings_api([]), cache=cache))
    assert body["path"] == "MY_BOOKINGS" and body["cache"] is None


def test_without_a_cache_store_is_null():
    service, _ = make(ScriptedLlm(preset_after("20:00")))
    service._cache = None  # local 처럼 Valkey 없이
    body = ask(service, QUESTION)
    assert body["path"] == "PRESET" and body["cache"] is None


# --- 응답에만 있는 칸 -----------------------------------------------------------


def test_response_keys_and_no_question_text_in_cache():
    service, _ = make(ScriptedLlm(preset_after("20:00")))
    for body in (ask(service, QUESTION), ask(service, QUESTION)):
        assert set(body) == CONTRACT_KEYS | {"sessionId"}
        assert set(body["cache"]) == {"entryId", "stored", "similarity"}
        assert "영화" not in json.dumps(body["cache"], ensure_ascii=False)  # 질문·틀 문장 없음


def test_cache_entry_session_and_log_are_unchanged(caplog):
    service, cache = make(ScriptedLlm(preset_after("20:00")))
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        first = ask(service, QUESTION)
        ask(service, QUESTION, session_id=first["sessionId"])
    key = asyncio.run(cache._v.keys("ai:exact:*"))[0]
    stored = json.loads(asyncio.run(cache._v.get(key)))
    assert key == exact_key(normalize(stored["template"]))  # 키·버전(ai:exact:v2:) 그대로
    assert key.startswith("ai:exact:v2:")
    assert set(stored) == {
        "origin", "preset_id", "sql", "subject", "kinds", "relative_words",
        "anchors", "template", "meaning",
    }  # fmt: skip
    session = json.loads(asyncio.run(cache._v.get(f"ai:session:user-1:{first['sessionId']}")))
    assert set(session) == {"question", "sql", "path"}
    answered = [r.fields for r in caplog.records if r.getMessage() == "chat answered"]
    assert len(answered) == 2
    for fields in answered:
        assert set(fields) == {
            "path", "presetId", "latencyMs", "llmCalls", "tokens", "questionLength",
            "questionHash", "sqlTemplate", "rowCount", "rejection",
        }  # fmt: skip
