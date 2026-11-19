"""문맥 이어받기 1단계(3.4.22): 답 아래 영화 버튼이 앞 답의 날짜·극장을 잃지 않는다.

가짜 Claude · fakeredis · 값을 아는 스냅샷(sql_fixtures: 오늘 11/17(화) 19:40, 극장 건대입구·강남).
철의 심장 회차: 11/17 강남 22:30 · 11/18 건대입구 14:00 · 11/19 강남 09:30 · 11/21 강남 15:00
"""

import asyncio
import json
import logging
import re
import uuid
from datetime import timedelta

import fakeredis
import pytest
from fastapi.testclient import TestClient

from seatsac_ai import context as ctx
from seatsac_ai.cache.slots import find_name
from seatsac_ai.cache.store import session_key
from seatsac_ai.cache.warm import internal_items, preset_items, warm
from seatsac_ai.config import Settings
from seatsac_ai.llm import orchestrator
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.main import create_app
from seatsac_ai.resources import context_templates, presets
from seatsac_ai.sql.conditions import TimeBound, analyze, analyze_only_fixed
from seatsac_ai.sql.validator import validate

from . import test_seat_hold as seat
from .conftest import USER as USER_HEADERS
from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store
from .test_cache_flow import NOW, USER, make

BUTTON = "철의 심장 어디서 몇 시에 해?"  # 화면의 영화 버튼이 보내는 문장(preset-actions.ts)
TOMORROW_KONDAE_SQL = (
    "SELECT m.title AS movie_title, s.starts_at FROM screening s "
    "JOIN movie m ON m.movie_id = s.movie_id JOIN theater t ON t.theater_id = s.theater_id "
    "WHERE s.business_date = '2026-11-18' AND t.name = '건대입구' "
    "AND s.starts_at > '2026-11-17 19:40' ORDER BY s.starts_at"
)
TWO_DAYS_KONDAE_SQL = TOMORROW_KONDAE_SQL.replace(
    "s.business_date = '2026-11-18'", "s.business_date IN ('2026-11-17', '2026-11-18')"
)
MOVIE_LIST_SQL = "SELECT title AS movie_title FROM movie ORDER BY title"


def tomorrow_kondae():
    return tool("run_sql", sql=TOMORROW_KONDAE_SQL, explanation="내일 건대입구 상영 영화")


def movie_everywhere():
    """조건 없이 그 영화 전체 — 이어받지 못했을 때 Claude가 고르는 프리셋(지금 동작)."""
    return tool("run_preset", presetId="movie_theaters", params={"movie": "철의 심장"})


def ask(service, question, session_id=None, continue_from=None, now=NOW, user=USER):
    return asyncio.run(
        service.answer(
            question, now=now, user_id=user, session_id=session_id, continue_from=continue_from
        )
    )


def pick(service, first, message=BUTTON, **kwargs):
    """first 답 아래의 영화 버튼을 누른다."""
    return ask(service, message, first["sessionId"], first["context"]["id"], **kwargs)


# --- 핵심 흐름 ------------------------------------------------------------------


def test_movie_button_keeps_the_date_and_theater_of_the_answer():
    llm = ScriptedLlm(tomorrow_kondae())
    service, _ = make(llm)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    assert first["path"] == "NL2SQL" and first["rowCount"] == 2
    assert first["context"] == {
        "id": first["context"]["id"],
        "date": "2026-11-18",
        "theater": "건대입구",
        "movie": None,
        "time": None,
        "timeDirection": None,
        "label": "내일 11/18(수) · 건대입구",
        "inherited": [],
    }
    picked = pick(service, first)
    # 내일 · 건대입구 · 그 영화만(조건이 없으면 4건이다)
    assert picked["rows"] == [["2026-11-18 14:00"]] and picked["screeningIds"] == [7]
    assert [c["name"] for c in picked["columns"]] == ["starts_at"]
    assert picked["llm"]["calls"] == 0 and len(llm.requests) == 1
    assert picked["context"]["inherited"] == ["date", "theater"]
    assert picked["context"]["label"] == "내일 11/18(수) · 건대입구"
    assert picked["context"]["date"] == "2026-11-18" and picked["context"]["theater"] == "건대입구"
    assert picked["context"]["movie"] == "철의 심장"
    assert picked["context"]["id"] != first["context"]["id"]
    assert picked["answer"].startswith("내일 11/18(수) 건대입구 철의 심장 회차: 1건입니다.")
    assert picked["path"] == "PRESET" and picked["presetId"] == "ctx_movie_date_theater"
    assert picked["sql"] is None and picked["sessionId"] == first["sessionId"]


def test_preset_answer_is_continued_the_same_way():
    """프리셋 답은 응답 sql 이 null 이다 — 조건은 프리셋 SQL과 빈칸 값에서 읽는다."""
    schedule = tool(
        "run_preset", presetId="theater_schedule",
        params={"date": "2026-11-18", "theater": "건대입구"},
    )  # fmt: skip
    llm = ScriptedLlm(schedule)
    service, _ = make(llm)
    first = ask(service, "내일 건대입구 편성표 보여줘")
    assert first["path"] == "PRESET" and first["context"]["label"] == "내일 11/18(수) · 건대입구"
    again = ask(service, "내일 건대입구 편성표 보여줘")  # 캐시 적중 답도 같은 조건을 남긴다
    assert again["path"] == "CACHE_EXACT"
    for answer in (first, again):
        picked = pick(service, answer)
        assert picked["rows"] == [["2026-11-18 14:00"]] and picked["llm"]["calls"] == 0
        assert picked["context"]["inherited"] == ["date", "theater"]
    assert len(llm.requests) == 1


def test_answer_with_only_a_date_uses_the_date_and_movie_template():
    on_date = tool("run_preset", presetId="movies_on_date", params={"date": "2026-11-18"})
    service, _ = make(ScriptedLlm(on_date))
    first = ask(service, "내일 상영하는 영화 뭐 있어?")
    assert first["context"]["theater"] is None and first["context"]["label"] == "내일 11/18(수)"
    picked = pick(service, first)
    assert picked["presetId"] == "ctx_movie_date" and picked["llm"]["calls"] == 0
    assert [c["name"] for c in picked["columns"]] == ["theater_name", "starts_at"]
    assert picked["rows"] == [["건대입구", "2026-11-18 14:00"]]
    assert picked["context"]["inherited"] == ["date"]
    assert picked["answer"].startswith("내일 11/18(수) 철의 심장 회차: 1건입니다.")


def test_answer_without_conditions_has_no_context_and_the_button_works_as_before():
    llm = ScriptedLlm(
        tool("run_sql", sql=MOVIE_LIST_SQL, explanation="상영 영화"), movie_everywhere()
    )
    service, _ = make(llm)
    first = ask(service, "상영 영화 목록")
    assert (
        first["rowCount"] == 4 and first["context"] is None
    )  # 화면은 continueFrom 을 보내지 않는다
    picked = ask(service, BUTTON, first["sessionId"])
    assert picked["path"] == "PRESET" and picked["presetId"] == "movie_theaters"
    assert picked["rowCount"] == 4 and picked["llm"]["calls"] == 1
    assert picked["context"]["inherited"] == [] and picked["context"]["movie"] == "철의 심장"


# --- 캐시 -----------------------------------------------------------------------


def test_warmed_cache_or_empty_cache_never_calls_claude():
    llm = ScriptedLlm(tomorrow_kondae())
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    service, cache = make(llm, valkey=valkey)
    asyncio.run(warm(cache, preset_items(presets()) + internal_items(context_templates())))
    first = ask(service, "건대입구 내일 영화 뭐해?")
    hit = pick(service, first)
    assert hit["path"] == "CACHE_EXACT" and hit["llm"]["calls"] == 0
    # 계약 cache: 캐시에서 나온 답은 그 항목을 가리킨다(예열 항목이라 저장한 답은 아니다)
    assert hit["cache"]["stored"] is False and hit["cache"]["similarity"] is None
    assert hit["presetId"] == "ctx_movie_date_theater" and "'2026-11-18'" in hit["sql"]

    async def drop_cache():
        for key in await valkey.keys("ai:exact:*"):
            await valkey.delete(key)

    asyncio.run(drop_cache())  # 캐시 수명이 지났거나 ai Valkey가 재부팅됐다
    miss = pick(service, first)
    assert miss["path"] == "PRESET" and miss["llm"]["calls"] == 0 and miss["cache"] is None
    assert miss["rows"] == hit["rows"] == [["2026-11-18 14:00"]]
    assert miss["context"]["inherited"] == hit["context"]["inherited"] == ["date", "theater"]
    assert len(llm.requests) == 1


@pytest.mark.parametrize("warmed", [True, False], ids=["예열 항목만 있는 캐시", "빈 캐시"])
def test_continued_answer_is_never_stored_in_the_shared_cache(warmed, monkeypatch):
    """이어받은 답은 한 사용자의 앞 조건이 섞인 답이다 — 모두가 같이 쓰는 캐시에 남기지 않는다."""
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    service, cache = make(ScriptedLlm(tomorrow_kondae()), valkey=valkey)
    if warmed:
        asyncio.run(warm(cache, preset_items(presets()) + internal_items(context_templates())))
    first = ask(service, "건대입구 내일 영화 뭐해?")  # 이 답(직접 친 질문)은 평소처럼 저장된다

    def snapshot():
        keys = sorted(asyncio.run(valkey.keys("ai:exact:*")) + asyncio.run(valkey.keys("ai:sem*")))
        values = [asyncio.run(valkey.get(k)) for k in keys if "exact" in k]
        return keys, values, dict(cache._index.docs)

    before = snapshot()
    writes: list[str] = []

    async def no_put(*args, **kwargs):
        writes.append("put")
        return True

    async def no_add(*args, **kwargs):
        writes.append("index.add")

    monkeypatch.setattr(cache, "put", no_put)
    monkeypatch.setattr(cache._index, "add", no_add)
    picked = pick(service, first)
    again = pick(service, first, "고양이 탐정 어디서 몇 시에 해?")
    assert picked["context"]["inherited"] == again["context"]["inherited"] == ["date", "theater"]
    assert picked["path"] == ("CACHE_EXACT" if warmed else "PRESET") and picked["llm"]["calls"] == 0
    assert writes == []  # cache.put·의미 색인 add 가 한 번도 불리지 않았다
    assert snapshot() == before  # 캐시의 키·값·의미 색인이 그대로다


def test_continued_answer_neither_reads_nor_writes_the_short_message_key():
    llm = ScriptedLlm(movie_everywhere(), tomorrow_kondae(), movie_everywhere())
    service, _ = make(llm)
    # 같은 문장을 직접 치면 조건 없는 답(4건)이 그 문장의 키로 캐시된다
    typed = ask(service, BUTTON)
    assert typed["rowCount"] == 4 and typed["path"] == "PRESET"
    first = ask(service, "건대입구 내일 영화 뭐해?")
    picked = pick(service, first)
    assert picked["rowCount"] == 1  # 캐시된 4건짜리 답을 읽지 않았다
    # 이어받은 답(1건)이 그 문장의 키를 덮어쓰지도 않았다 — 직접 치면 여전히 4건(캐시)
    again = ask(service, BUTTON)
    assert again["path"] == "CACHE_EXACT" and again["rowCount"] == 4
    assert again["context"]["inherited"] == []
    assert len(llm.requests) == 2


def test_cache_entry_from_claude_under_the_template_key_is_not_used():
    """같은 키에 Claude가 답한 항목(다른 열)이 있어도 버튼의 답은 틀의 열 그대로다."""
    with_room = (
        "SELECT s.auditorium_name, s.starts_at FROM screening s "
        "JOIN movie m ON m.movie_id = s.movie_id JOIN theater t ON t.theater_id = s.theater_id "
        "WHERE s.business_date = '2026-11-18' AND t.name = '건대입구' AND m.title = '철의 심장' "
        "AND s.starts_at > '2026-11-17 19:40'"
    )
    llm = ScriptedLlm(tool("run_sql", sql=with_room, explanation="회차"), tomorrow_kondae())
    service, _ = make(llm)
    typed = ask(service, "11월 18일 건대입구 철의 심장 회차")
    assert [c["name"] for c in typed["columns"]] == ["auditorium_name", "starts_at"]
    picked = pick(service, ask(service, "건대입구 내일 영화 뭐해?"))
    assert picked["path"] == "PRESET" and [c["name"] for c in picked["columns"]] == ["starts_at"]


# --- 어느 답에 이어지는가 ----------------------------------------------------------


def test_button_of_an_older_answer_uses_that_answers_conditions():
    today_gangnam = tool(
        "run_preset", presetId="theater_schedule", params={"date": "2026-11-17", "theater": "강남"}
    )
    llm = ScriptedLlm(tomorrow_kondae(), today_gangnam)
    service, _ = make(llm)
    older = ask(service, "건대입구 내일 영화 뭐해?")
    newer = ask(service, "오늘 강남 편성표 보여줘", older["sessionId"])
    assert newer["context"]["label"] == "오늘 11/17(화) · 강남"
    from_newer = pick(service, newer)
    assert from_newer["rows"] == [["2026-11-17 22:30"]]
    from_older = pick(service, older)  # 위로 올려 예전 답의 버튼을 눌렀다
    assert from_older["rows"] == [["2026-11-18 14:00"]]
    assert from_older["context"]["label"] == "내일 11/18(수) · 건대입구"
    assert len(llm.requests) == 2


def test_only_the_last_five_answers_are_kept():
    known: list[dict] = []
    for day in range(1, 8):
        known = ctx.appended(known, ctx.new_entry(known, {"date": f"2026-11-{day:02d}"}))
    assert [e["date"][-2:] for e in known] == ["03", "04", "05", "06", "07"]
    assert len({e["id"] for e in known}) == 5 and all(len(e["id"]) == 6 for e in known)


@pytest.mark.parametrize("case", ["unknown_id", "expired_session", "other_user"])
def test_unusable_continue_from_is_answered_independently_without_error(case):
    llm = ScriptedLlm(tomorrow_kondae(), movie_everywhere())
    service, _ = make(llm)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    session_id, context_id, user = first["sessionId"], first["context"]["id"], USER
    if case == "unknown_id":
        context_id = "zzzzzz"
    elif case == "expired_session":
        session_id = str(uuid.uuid4())  # 30분이 지나 세션이 사라졌다
    else:
        user = "user-2"  # 남의 sessionId·continueFrom 을 보냈다 — 그 사용자 키에는 닿지 않는다
    picked = ask(service, BUTTON, session_id, context_id, user=user)
    assert picked["path"] == "PRESET" and picked["presetId"] == "movie_theaters"
    assert picked["rowCount"] == 4 and picked["llm"]["calls"] == 1
    assert picked["context"]["inherited"] == []  # 화면이 「이전 조건이 만료…」를 안내한다
    if case != "unknown_id":
        assert picked["sessionId"] != first["sessionId"]


def test_continue_from_without_a_session_store_is_ignored():
    """캐시(ai Valkey)가 없는 local: 세션이 없어 context 는 늘 null, continueFrom 은 무시."""
    service = ChatService(loaded_store(), ScriptedLlm(movie_everywhere()), Settings(_env_file=None))
    body = asyncio.run(service.answer(BUTTON, now=NOW, user_id=USER, continue_from="k7f2a1"))
    assert body["rowCount"] == 4 and body["context"] is None


# --- 문맥이 남는 경우·버리는 경우 ---------------------------------------------------


def test_context_survives_a_clarify_and_side_questions():
    llm = ScriptedLlm(tomorrow_kondae(), tool("ask_user"), tool("out_of_scope", reason="x"))
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    service, _ = make(llm, valkey=valkey)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    clarify = ask(service, "자리 많은 회차", first["sessionId"])
    assert clarify["path"] == "CLARIFY" and clarify["context"] is None
    side = ask(service, "안녕", first["sessionId"])
    assert side["path"] == "OUT_OF_SCOPE" and side["context"] is None
    session = json.loads(asyncio.run(valkey.get(session_key(USER, first["sessionId"]))))
    assert session["path"] == "CLARIFY" and [e["id"] for e in session["ctx"]] == [
        first["context"]["id"]
    ]
    # 되묻기 직후의 짧은 답은 원래 질문에 붙여 다시 묻는다. 버튼(continueFrom)은 그러지 않는다
    picked = pick(service, first, "철의 심장 어디서 해?")
    assert picked["rows"] == [["2026-11-18 14:00"]] and picked["llm"]["calls"] == 0
    assert picked["context"]["inherited"] == ["date", "theater"]
    assert len(llm.requests) == 3


def test_session_keeps_the_completed_sentence_and_the_new_context():
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    service, _ = make(ScriptedLlm(tomorrow_kondae()), valkey=valkey)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    picked = pick(service, first)
    session = json.loads(asyncio.run(valkey.get(session_key(USER, first["sessionId"]))))
    # 후속 질문("그중 …")이 조건을 알 수 있게 완성 문장을 남긴다
    assert session["question"] == "11월 18일 건대입구 철의 심장 회차"
    no_time = {"time": None, "timeDirection": None}
    assert session["ctx"] == [
        {"id": first["context"]["id"], "date": "2026-11-18", "theater": "건대입구", "movie": None}
        | no_time,
        {
            "id": picked["context"]["id"],
            "date": "2026-11-18",
            "theater": "건대입구",
            "movie": "철의 심장",
        }
        | no_time,
    ]


def test_date_that_has_passed_is_dropped_but_the_theater_is_kept():
    llm = ScriptedLlm(tomorrow_kondae())
    service, _ = make(llm)
    first = ask(service, "건대입구 내일 영화 뭐해?")  # 내일 = 11/18
    picked = pick(service, first, now=NOW + timedelta(days=2))  # 11/19 에 눌렀다
    assert picked["presetId"] == "ctx_movie_theater" and picked["llm"]["calls"] == 0
    assert picked["context"]["inherited"] == ["theater"] and picked["context"]["date"] is None
    assert picked["context"]["label"] == "건대입구"


def test_same_day_after_midnight_keeps_the_absolute_date():
    """23:50에 「내일」을 묻고 자정을 넘겨 눌러도 하루가 밀리지 않는다(11/18 그대로)."""
    service, _ = make(ScriptedLlm(tomorrow_kondae()))
    first = ask(service, "건대입구 내일 영화 뭐해?")
    picked = pick(service, first, now=NOW + timedelta(hours=5))  # 11/18 00:40
    assert picked["rows"] == [["2026-11-18 14:00"]]
    assert picked["context"]["label"] == "오늘 11/18(수) · 건대입구"


def test_theater_gone_from_the_snapshot_is_dropped_but_the_date_is_kept():
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    service, _ = make(ScriptedLlm(tomorrow_kondae()), valkey=valkey)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    key = session_key(USER, first["sessionId"])
    session = json.loads(asyncio.run(valkey.get(key)))
    session["ctx"][0]["theater"] = "부산본점"  # 답한 뒤 스냅샷에서 내려간 극장
    asyncio.run(valkey.set(key, json.dumps(session, ensure_ascii=False)))
    picked = pick(service, first)
    assert picked["presetId"] == "ctx_movie_date" and picked["context"]["inherited"] == ["date"]
    assert picked["context"]["theater"] is None


def test_movie_that_is_not_in_the_snapshot_goes_the_usual_way():
    llm = ScriptedLlm(tomorrow_kondae(), tool("out_of_scope", reason="x"))
    service, _ = make(llm)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    picked = pick(service, first, "부활남 어디서 몇 시에 해?")  # 버튼을 누르기 전에 내려간 영화
    assert picked["path"] == "OUT_OF_SCOPE" and picked["llm"]["calls"] == 1
    assert picked["context"] is None


def test_answer_over_two_dates_does_not_pass_on_a_date():
    two_days = tool("run_sql", sql=TWO_DAYS_KONDAE_SQL, explanation="건대입구 이틀 상영 영화")
    service, _ = make(ScriptedLlm(two_days))
    first = ask(service, "건대입구 이틀 동안 영화 뭐해?")
    assert first["context"]["date"] is None and first["context"]["label"] == "건대입구"
    picked = pick(service, first)
    assert picked["presetId"] == "ctx_movie_theater"
    assert picked["context"]["inherited"] == ["theater"]


def test_message_with_a_condition_the_templates_cannot_hold_is_not_continued():
    llm = ScriptedLlm(tomorrow_kondae(), movie_everywhere())
    service, _ = make(llm)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    picked = pick(service, first, "철의 심장 20시 이후 회차")  # 시각은 틀에 없다
    assert picked["llm"]["calls"] == 1 and picked["context"]["inherited"] == []


# --- 이 답의 조건이라고 말할 수 없는 SQL ---------------------------------------------

_JOINED = (
    "FROM screening s JOIN movie m ON m.movie_id = s.movie_id "
    "JOIN theater t ON t.theater_id = s.theater_id "
)
_KONDAE_TOMORROW = (
    f"SELECT s.movie_id {_JOINED}WHERE t.name = '건대입구' AND s.business_date = '2026-11-18'"
)
# 건대입구·11/18 이 SQL 안에 있지만 이 답이 「건대입구 11/18」을 조회한 것은 아니다
NOT_THE_ANSWERS_CONDITION = {
    "NOT IN": (
        f"SELECT m.title AS movie_title FROM movie m WHERE m.movie_id NOT IN ({_KONDAE_TOMORROW})"
    ),
    "NOT EXISTS": (
        "SELECT m.title AS movie_title FROM movie m WHERE NOT EXISTS (SELECT 1 FROM screening s "
        "JOIN theater t ON t.theater_id = s.theater_id WHERE s.movie_id = m.movie_id "
        "AND t.name = '건대입구' AND s.business_date = '2026-11-18')"
    ),
    "IN (서브쿼리)": (
        f"SELECT m.title AS movie_title, s.starts_at {_JOINED}"
        f"WHERE t.name = '강남' AND s.movie_id IN ({_KONDAE_TOMORROW})"
    ),
    "UNION ALL": (
        f"SELECT m.title AS movie_title, s.starts_at {_JOINED}"
        "WHERE t.name = '건대입구' AND s.business_date = '2026-11-18' UNION ALL "
        f"SELECT m.title AS movie_title, s.starts_at {_JOINED}WHERE m.title = '철의 심장'"
    ),
    "CTE": (
        f"WITH k AS ({_KONDAE_TOMORROW}) "
        "SELECT m.title AS movie_title FROM movie m JOIN k ON k.movie_id = m.movie_id"
    ),
}  # fmt: skip


@pytest.mark.parametrize("shape", NOT_THE_ANSWERS_CONDITION)
def test_conditions_inside_subquery_union_or_cte_are_not_this_answers_context(shape):
    """서브쿼리·UNION·CTE 안의 조건은 뜻이 뒤집힐 수 있다(NOT IN = 그 극장을 뺀 답)."""
    sql = NOT_THE_ANSWERS_CONDITION[shape]
    assert analyze_only_fixed(validate(sql)) is None
    # 의미 검사·잔여석 범위가 쓰는 analyze 는 그대로다 — SQL 어딘가의 건대입구를 읽는다
    assert analyze(validate(sql)).has_theater("건대입구") is True
    service, _ = make(ScriptedLlm(tool("run_sql", sql=sql, explanation="영화")))
    body = ask(service, "건대입구 영화 관련 질문")
    assert body["path"] == "NL2SQL" and body["context"] is None


@pytest.mark.parametrize(
    "extra",
    [
        "t.region = '서울'",
        "s.screen_type = 'RECLINER'",
        "m.view_grade = '15'",
        # 시각: 「○시 이후/이전 하나」(같은 날짜)만 읽는다 — 아래 모양은 여전히 문맥을 남기지 않는다
        "s.starts_at >= '2026-11-18 20:00' AND s.starts_at < '2026-11-18 22:00'",  # 범위 두 개
        "s.starts_at BETWEEN '2026-11-18 20:00' AND '2026-11-18 22:00'",
        "s.starts_at >= '2026-11-19 20:00'",  # 날짜가 다른 시각(답의 날짜는 11/18)
        "s.starts_at >= '2026-11-18 20:00:00'",  # 초까지 적은 글자
        "s.starts_at >= '2026-11-18'",  # 시각 없는 글자
        "(s.starts_at >= '2026-11-18 20:00' OR s.starts_at < '2026-11-18 10:00')",
        "NOT s.starts_at >= '2026-11-18 20:00'",
        "time(s.starts_at) >= '20:00'",
        "s.ends_at >= '2026-11-18 20:00'",
        "s.start_hour >= 20",
        "s.start_hour < 10",
        "s.movie_id = 4",
        "(m.title = '철의 심장' OR m.title = '한강의 밤')",
        "s.remaining_seats > 10",
    ],
)
def test_answer_with_a_condition_that_cannot_be_inherited_leaves_no_context(extra):
    """지역·상영관 종류·등급·시각 등은 이어받지 못한다 — 일부만 이어받으면 앞 답보다 넓어진다."""
    sql = (
        f"SELECT m.title AS movie_title, s.starts_at {_JOINED}"
        f"WHERE s.business_date = '2026-11-18' AND t.name = '건대입구' AND {extra} "
        "AND s.starts_at > '2026-11-17 19:40'"
    )
    assert analyze_only_fixed(validate(sql), {"now": "2026-11-17 19:40"}) is None
    plain = sql.replace(f"AND {extra} ", "")
    assert analyze_only_fixed(validate(plain), {"now": "2026-11-17 19:40"}) is not None


def test_single_time_bound_on_the_answers_date_is_the_only_time_condition_read():
    """위 표의 맨 첫 줄과 짝: 같은 날짜의 「○시 이후」 하나는 읽는다(Conditions.time)."""
    sql = (
        f"SELECT m.title AS movie_title, s.starts_at {_JOINED}"
        "WHERE s.business_date = '2026-11-18' AND t.name = '건대입구' "
        "AND s.starts_at >= '2026-11-18 20:00' AND s.starts_at > '2026-11-17 19:40'"
    )
    conditions = analyze_only_fixed(validate(sql), {"now": "2026-11-17 19:40"})
    assert conditions.time == TimeBound("2026-11-18", "20:00", "after")
    assert analyze(validate(sql)).time is None  # 의미 검사용 analyze 는 시각을 읽지 않는다(그대로)


@pytest.mark.parametrize("preset_id", ["late_night_today", "morning_today"])
def test_preset_with_a_fixed_time_range_leaves_no_context(preset_id):
    """심야·조조처럼 시각 범위가 SQL에 고정된 답(start_hour)은 이어받지 않는다."""
    service, _ = make(ScriptedLlm(tool("run_preset", presetId=preset_id, params={})))
    body = ask(service, "오늘 심야 영화 알려줘")
    assert body["path"] == "PRESET" and body["context"] is None


def test_failure_while_reading_conditions_never_breaks_the_answer(monkeypatch, caplog):
    def boom(*_):
        raise RuntimeError("건대입구 2026-11-18 SELECT")  # 메시지에 질문·SQL 값이 들어 있다고 치자

    monkeypatch.setattr(orchestrator, "analyze_only_fixed", boom)
    service, _ = make(ScriptedLlm(tomorrow_kondae()))
    with caplog.at_level(logging.WARNING, logger="seatsac_ai.llm.orchestrator"):
        body = ask(service, "건대입구 내일 영화 뭐해?")
    assert body["path"] == "NL2SQL" and body["rowCount"] == 2 and body["context"] is None
    assert "답의 조건을 읽지 못했다(RuntimeError)" in caplog.text
    assert "건대입구" not in caplog.text and "SELECT" not in caplog.text


def test_template_that_fails_to_run_falls_back_to_the_usual_path(monkeypatch):
    llm = ScriptedLlm(tomorrow_kondae(), movie_everywhere())
    service, _ = make(llm)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    template_sql = next(
        t.sql for t in context_templates() if t.preset_id == "ctx_movie_date_theater"
    )
    execute = service._execute

    async def broken_template(sql, *args, **kwargs):
        if sql == template_sql:
            raise orchestrator._Retry("SQL 실행 오류")  # 스냅샷 모양이 바뀌었다고 치자
        return await execute(sql, *args, **kwargs)

    monkeypatch.setattr(service, "_execute", broken_template)
    picked = pick(service, first)
    # 이어받지 못했다 — 오류가 아니라 독립 질문으로(지금 동작) 답한다
    assert picked["path"] == "PRESET" and picked["presetId"] == "movie_theaters"
    assert picked["rowCount"] == 4 and picked["llm"]["calls"] == 1
    assert picked["context"]["inherited"] == []


# --- 값 다루기(context.py) --------------------------------------------------------


@pytest.mark.parametrize("title", ["7일의 왕비", "8시 뉴스", "11월의 비"])
def test_title_that_looks_like_a_date_or_time_is_read_as_a_title(title):
    entry = {"id": "k7f2a1", "date": "2026-11-18", "theater": "건대입구", "movie": None}
    plan = ctx.plan(entry, f"{title} 어디서 몇 시에 해?", NOW, ["건대입구", "강남"], [title])
    assert plan is not None and plan.inherited == ("date", "theater")
    assert plan.values == {"movie": title, "date": "2026-11-18", "theater": "건대입구"}


def test_value_said_in_the_new_message_wins_over_the_context():
    entry = {"id": "k7f2a1", "date": "2026-11-18", "theater": "건대입구", "movie": "한강의 밤"}
    plan = ctx.plan(
        entry, "모레 철의 심장 회차", NOW, ["건대입구", "강남"], ["철의 심장", "한강의 밤"]
    )
    assert plan.values == {"movie": "철의 심장", "date": "2026-11-19", "theater": "건대입구"}
    assert plan.inherited == ("theater",)
    assert plan.sentence == "11월 19일 건대입구 철의 심장 회차"


def test_two_titles_of_the_same_length_are_not_guessed():
    """길이가 같은 제목이 둘 다 글에 있으면 고르지 않는다(실행할 때마다 달라지지 않게)."""
    movies = ["한강의 밤", "철의 심장", "밤"]
    assert find_name("철의 심장 한강의 밤 어디서 해?", movies) is None
    assert find_name("한강의 밤 어디서 해?", movies) == ("한강의 밤", "한강의 밤")  # 긴 이름이 먼저
    entry = {"id": "k7f2a1", "date": "2026-11-18", "theater": "건대입구", "movie": None}
    assert ctx.plan(entry, "철의 심장 한강의 밤 어디서 해?", NOW, ["건대입구"], movies) is None


def test_nothing_to_inherit_means_no_plan():
    movies = ["철의 심장"]
    only_movie = {"id": "k7f2a1", "date": None, "theater": None, "movie": "철의 심장"}
    assert ctx.plan(only_movie, BUTTON, NOW, ["건대입구"], movies) is None
    with_date = {"id": "k7f2a1", "date": "2026-11-18", "theater": None, "movie": None}
    assert (
        ctx.plan(with_date, "어디서 몇 시에 해?", NOW, ["건대입구"], movies) is None
    )  # 영화가 없다


@pytest.mark.parametrize(
    ("where", "expected"),
    [
        (
            "s.business_date = '2026-11-18' AND t.name LIKE '%건대%'",
            ("2026-11-18", "건대입구", None),
        ),
        ("s.theater_id = 20 AND m.title = '철의 심장'", (None, "강남", "철의 심장")),
        ("s.business_date = :today AND t.name IN ('건대입구', '강남')", ("2026-11-17", None, None)),
        ("t.name = '건대입구' OR t.name = '강남'", (None, None, None)),
        ("s.business_date >= '2026-11-18'", (None, None, None)),
        ("s.business_date = '11월 18일' AND t.name = '없는 지점'", (None, None, None)),
    ],
)
def test_only_conditions_fixed_to_one_value_become_context(where, expected):
    sql = (
        "SELECT s.starts_at FROM screening s JOIN movie m ON m.movie_id = s.movie_id "
        f"JOIN theater t ON t.theater_id = s.theater_id WHERE {where}"
    )
    conditions = analyze(validate(sql), {"today": "2026-11-17"})
    theaters, movies = [(10, "건대입구"), (20, "강남")], ["철의 심장", "한강의 밤"]
    values = ctx.from_conditions(conditions, theaters, movies)
    assert (values["date"], values["theater"], values["movie"]) == expected


def test_session_from_before_this_change_has_no_context():
    assert ctx.entries(None) == [] and ctx.entries({"question": "q", "sql": None}) == []
    assert (
        ctx.entries({"ctx": "x"}) == [] and ctx.entries({"ctx": [{"date": "2026-11-18"}, 3]}) == []
    )


# --- 직접 친 질문은 그대로다 --------------------------------------------------------

TYPED = [
    ("오늘 20시 이후 영화 알려줘", lambda: tool("run_preset", presetId="today_after_time",
                                                params={"time": "20:00"})),
    (BUTTON, movie_everywhere),
    ("상영 영화 목록", lambda: tool("run_sql", sql=MOVIE_LIST_SQL, explanation="상영 영화")),
    ("내일 날씨", lambda: tool("out_of_scope", reason="x")),
    ("자리 많은 회차", lambda: tool("ask_user")),
]  # fmt: skip


def _same(body):
    """세션·시간·문맥 id 처럼 요청마다 다른 값은 빼고 견준다.

    asOf(와 답 문장 끝의 「○시 ○분 기준」)는 스냅샷을 만든 실제 시각이라 서비스마다 다르다.
    """
    body = {k: v for k, v in body.items() if k not in ("sessionId", "latencyMs", "asOf")}
    body["answer"] = re.sub(r"\(\d+시 \d+분 기준\)", "(기준)", body["answer"])
    if body["context"]:
        body["context"] = body["context"] | {"id": "-"}
    return body


@pytest.mark.parametrize(("question", "reply"), TYPED, ids=[q for q, _ in TYPED])
def test_typed_question_is_answered_the_same_with_or_without_stored_context(question, reply):
    """세션에 앞 답의 조건이 있어도 continueFrom 없는 질문은 그 조건을 쓰지 않는다."""
    fresh, _ = make(ScriptedLlm(reply()))
    alone = ask(fresh, question)
    service, _ = make(ScriptedLlm(tomorrow_kondae(), reply()))
    first = ask(service, "건대입구 내일 영화 뭐해?")
    after = ask(service, question, first["sessionId"])
    assert _same(after) == _same(alone)
    assert (after["context"] or {}).get("inherited", []) == []


def test_follow_up_still_gets_the_previous_question_and_sql():
    llm = ScriptedLlm(tomorrow_kondae(), tool("out_of_scope", reason="x"))
    service, _ = make(llm)
    first = ask(service, "건대입구 내일 영화 뭐해?")
    ask(service, "그중 14시 이후만", first["sessionId"])
    text = llm.requests[1]["messages"][0]["content"]
    assert "직전 질문: 건대입구 내일 영화 뭐해?" in text and "직전 SQL: SELECT" in text


# --- API ----------------------------------------------------------------------


def _client(*replies):
    settings = Settings(_env_file=None, APP_ENV="local")
    llm = ScriptedLlm(*replies)
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    app = create_app(settings, llm=llm, valkey=valkey)
    service, _ = make(llm, valkey=valkey)
    app.state.chat = service
    return TestClient(app, raise_server_exceptions=False)


def test_api_takes_continue_from_and_returns_context():
    client = _client(tomorrow_kondae())
    first = client.post(
        "/ai/chat", json={"message": "건대입구 내일 영화 뭐해?"}, headers=USER_HEADERS
    ).json()
    assert first["context"]["label"] == "내일 11/18(수) · 건대입구"
    request = {
        "message": BUTTON,
        "sessionId": first["sessionId"],
        "continueFrom": first["context"]["id"],
    }
    picked = client.post("/ai/chat", json=request, headers=USER_HEADERS)
    assert picked.status_code == 200
    body = picked.json()
    assert body["rows"] == [["2026-11-18 14:00"]] and body["llm"]["calls"] == 0
    assert body["context"]["inherited"] == ["date", "theater"]


def test_api_ignores_an_unknown_continue_from_and_rejects_only_a_wrong_shape():
    client = _client(movie_everywhere())
    unknown = client.post(
        "/ai/chat", json={"message": BUTTON, "continueFrom": "nope"}, headers=USER_HEADERS
    )
    assert unknown.status_code == 200 and unknown.json()["rowCount"] == 4
    assert unknown.json()["context"]["inherited"] == []
    too_long = client.post(
        "/ai/chat", json={"message": BUTTON, "continueFrom": "x" * 65}, headers=USER_HEADERS
    )
    assert too_long.status_code == 400
    assert too_long.json()["details"] == [{"field": "continueFrom", "reason": "LENGTH"}]


# --- 시각 조건 이어받기: 「○시 이후/이전」 하나 --------------------------------------
# 오늘(11/17 화, 지금 19:40) 회차: 별의 항해 건대입구 20:00 · 고양이 탐정 강남 19:50 ·
# 한강의 밤 건대입구 21:00 · 철의 심장 강남 22:30 · 한강의 밤 강남 00:30(심야, 영업일 11/17)

HANGANG = "한강의 밤 어디서 몇 시에 해?"


def after(time):
    return tool("run_preset", presetId="today_after_time", params={"time": time})


def today_sql(where, theater=False):
    """Claude가 쓴 모양(리터럴): 오늘 + where + 지금 이후."""
    kondae = "AND t.name = '건대입구' " if theater else ""
    return (
        "SELECT m.title AS movie_title, s.starts_at FROM screening s "
        "JOIN movie m ON m.movie_id = s.movie_id JOIN theater t ON t.theater_id = s.theater_id "
        f"WHERE s.business_date = '2026-11-17' {kondae}AND {where} "
        "AND s.starts_at > '2026-11-17 19:40' ORDER BY s.starts_at"
    )


def run_sql(sql):
    return tool("run_sql", sql=sql, explanation="오늘 영화")


def exact_keys(valkey):
    return sorted(asyncio.run(valkey.keys("ai:exact:*")))


def test_movie_button_after_today_after_time_preset_keeps_the_date_and_the_time():
    """추천 버튼 「오늘 ○시 이후 회차」 → 영화 고르기: 가장 흔한 흐름."""
    llm = ScriptedLlm(after("22:00"))
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    service, _ = make(llm, valkey=valkey)
    first = ask(service, "오늘 22시 이후에 시작하는 영화 알려줘")
    assert first["path"] == "PRESET" and first["presetId"] == "today_after_time"
    assert first["context"] == {
        "id": first["context"]["id"],
        "date": "2026-11-17",
        "theater": None,
        "movie": None,
        "time": "22:00",
        "timeDirection": "after",
        "label": "오늘 11/17(화) · 22시 이후",
        "inherited": [],
    }
    stored = exact_keys(valkey)
    picked = pick(service, first, HANGANG)
    # 오늘 · 22시 이후 · 그 영화만 — 건대입구 21:00 은 빠지고 자정 넘은 심야 00:30 만 남는다
    assert picked["rows"] == [["강남", "2026-11-18 00:30"]] and picked["screeningIds"] == [6]
    assert picked["presetId"] == "ctx_movie_date_after" and picked["path"] == "PRESET"
    assert picked["llm"]["calls"] == 0 and len(llm.requests) == 1
    assert picked["context"] == {
        "id": picked["context"]["id"],
        "date": "2026-11-17",
        "theater": None,
        "movie": "한강의 밤",
        "time": "22:00",
        "timeDirection": "after",
        "label": "오늘 11/17(화) · 22시 이후",
        "inherited": ["date", "time"],
    }
    assert picked["answer"].startswith("오늘 11/17(화) 22시 이후 한강의 밤 회차: 1건입니다.")
    # 이어받은 답은 공용 캐시에 저장하지 않는다(carried)
    assert picked["cache"] is None and exact_keys(valkey) == stored
    other = pick(service, first)  # 같은 답의 다른 영화 버튼
    assert other["rows"] == [["강남", "2026-11-17 22:30"]] and other["llm"]["calls"] == 0
    session = json.loads(asyncio.run(valkey.get(session_key(USER, first["sessionId"]))))
    assert session["question"] == "11월 17일 철의 심장 22:00 이후 회차"  # 후속 질문이 조건을 안다
    assert session["ctx"][-1]["time"] == "22:00" and session["ctx"][-1]["timeDirection"] == "after"


def test_cache_hit_of_the_same_preset_question_leaves_the_same_time():
    """캐시 적중 답의 SQL은 `'2026-11-17' || ' ' || '22:00'` 모양이다 — 그것도 읽는다."""
    llm = ScriptedLlm(after("22:00"))
    service, _ = make(llm)
    ask(service, "오늘 22시 이후 영화 알려줘")
    hit = ask(service, "오늘 22시 이후 영화 알려줘")
    assert hit["path"] == "CACHE_EXACT" and "|| ' ' ||" in hit["sql"]
    assert hit["context"]["label"] == "오늘 11/17(화) · 22시 이후"
    other_time = ask(service, "오늘 21시 이후 영화 알려줘")  # 같은 틀, 다른 시각
    assert other_time["path"] == "CACHE_EXACT" and other_time["context"]["time"] == "21:00"
    picked = pick(service, hit, HANGANG)
    assert picked["rows"] == [["강남", "2026-11-18 00:30"]] and len(llm.requests) == 1
    assert pick(service, other_time, HANGANG)["rowCount"] == 2  # 21:00 · 00:30


def test_nl2sql_answer_with_a_theater_and_a_literal_time_is_continued_the_same_way():
    llm = ScriptedLlm(run_sql(today_sql("s.starts_at >= '2026-11-17 20:30'", theater=True)))
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    service, _ = make(llm, valkey=valkey)
    first = ask(service, "오늘 건대입구 20시 30분 이후 영화 뭐 있어?")
    assert first["path"] == "NL2SQL" and first["rowCount"] == 1
    assert first["context"]["label"] == "오늘 11/17(화) · 건대입구 · 20시 30분 이후"
    assert (first["context"]["time"], first["context"]["timeDirection"]) == ("20:30", "after")
    stored = exact_keys(valkey)
    picked = pick(service, first, HANGANG)
    assert picked["rows"] == [["2026-11-17 21:00"]] and picked["llm"]["calls"] == 0
    assert picked["presetId"] == "ctx_movie_date_theater_after"
    assert picked["context"]["inherited"] == ["date", "theater", "time"]
    assert picked["context"]["label"] == "오늘 11/17(화) · 건대입구 · 20시 30분 이후"
    assert picked["answer"].startswith(
        "오늘 11/17(화) 건대입구 20시 30분 이후 한강의 밤 회차: 1건입니다."
    )
    assert picked["cache"] is None and exact_keys(valkey) == stored
    # 시각이 정말 걸렸다: 건대입구 20:00 인 별의 항해는 20시 30분 이후에 없다(시각이 없으면 1건)
    early = pick(service, first, "별의 항해 어디서 몇 시에 해?")
    assert early["rowCount"] == 0 and early["llm"]["calls"] == 0 and len(llm.requests) == 1
    # 이어받은 답 아래의 버튼도 같은 조건을 이어받는다
    again = pick(service, picked, HANGANG)
    assert again["rows"] == [["2026-11-17 21:00"]]
    assert again["context"]["inherited"] == ["date", "theater", "time"]


@pytest.mark.parametrize(
    ("question", "where"),
    [
        ("오늘 21시 이전 영화 뭐 있어?", "s.starts_at <= '2026-11-17 21:00'"),
        ("오늘 21시 전에 하는 영화", "'2026-11-17 21:00' >= s.starts_at"),
    ],
)
def test_before_bound_is_continued_with_the_before_template(question, where):
    llm = ScriptedLlm(run_sql(today_sql(where)))
    service, _ = make(llm)
    first = ask(service, question)
    assert first["context"]["label"] == "오늘 11/17(화) · 21시 이전"
    picked = pick(service, first, HANGANG)
    # 건대입구 21:00 만 — 자정 넘은 00:30 은 「21시 이전」이 아니다
    assert picked["rows"] == [["건대입구", "2026-11-17 21:00"]] and picked["llm"]["calls"] == 0
    assert picked["presetId"] == "ctx_movie_date_before"
    assert picked["context"]["timeDirection"] == "before"


def test_warmed_cache_serves_the_time_templates_too():
    llm = ScriptedLlm()  # Claude 답이 없다 — 부르면 실패한다
    service, cache = make(llm)
    asyncio.run(warm(cache, preset_items(presets()) + internal_items(context_templates())))
    first = ask(service, "오늘 22시 이후에 시작하는 영화 알려줘")
    assert first["path"] == "CACHE_EXACT" and first["context"]["time"] == "22:00"
    picked = pick(service, first, HANGANG)
    assert picked["path"] == "CACHE_EXACT" and picked["presetId"] == "ctx_movie_date_after"
    assert picked["rows"] == [["강남", "2026-11-18 00:30"]] and llm.requests == []
    assert picked["cache"]["stored"] is False and "'22:00'" in picked["sql"]


# 반증: 틀린 시각 조건이 조용히 붙지 않는다 — 아래는 모두 「문맥 자체가 없다」
NO_CONTEXT_FOR_TIME = {
    "시각 범위 두 개": (
        "오늘 20시부터 22시 사이 영화",
        today_sql("s.starts_at >= '2026-11-17 20:00' AND s.starts_at < '2026-11-17 22:00'"),
    ),
    "start_hour 표현": ("오늘 20시 이후 영화", today_sql("s.start_hour >= 20")),
    "SQL의 시각이 질문의 시각과 다름": (
        "오늘 20시 이후 영화",
        today_sql("s.starts_at >= '2026-11-17 21:00'"),
    ),
    "질문에 시각이 없는데 SQL에만 있음": (
        "오늘 저녁 영화 뭐 있어?",
        today_sql("s.starts_at >= '2026-11-17 20:00'"),
    ),
    "지금 시각을 1분 다르게 적은 글자(시각 조건 아님)": (
        "오늘 영화 뭐 있어?",
        today_sql("s.starts_at > '2026-11-17 19:41'"),
    ),
    "질문은 이전인데 SQL은 이후": (
        "오늘 20시 이전 영화",
        today_sql("s.starts_at >= '2026-11-17 20:00'"),
    ),
    "질문은 이후인데 SQL은 이전": (
        "오늘 20시 이후 영화",
        today_sql("s.starts_at < '2026-11-17 20:00'"),
    ),
    "질문에 이후·이전 말이 없음": (
        "오늘 20시 영화",
        today_sql("s.starts_at >= '2026-11-17 20:00'"),
    ),
    "질문에 이후와 이전이 다 있음": (
        "오늘 20시 이후 영화 마감 전에 알려줘",
        today_sql("s.starts_at >= '2026-11-17 20:00'"),
    ),
    "질문의 시각이 둘인데 SQL에는 하나": (
        "오늘 20시부터 22시까지 영화",
        today_sql("s.starts_at >= '2026-11-17 20:00'"),
    ),
    "날짜 없는 시각 조건": (
        "20시 이후 영화",
        today_sql("s.starts_at >= '2026-11-17 20:00'").replace(
            "s.business_date = '2026-11-17' AND ", ""
        ),
    ),
    "날짜가 둘인 답의 시각 조건": (
        "오늘 내일 20시 이후 영화",
        today_sql("s.starts_at >= '2026-11-17 20:00'").replace(
            "= '2026-11-17'", "IN ('2026-11-17', '2026-11-18')"
        ),
    ),
    "서브쿼리 안의 시각 조건": (
        "오늘 20시 이후 영화",
        "SELECT m.title AS movie_title FROM movie m WHERE m.movie_id IN (SELECT s.movie_id "
        "FROM screening s WHERE s.business_date = '2026-11-17' "
        "AND s.starts_at >= '2026-11-17 20:00')",
    ),
    "OR로 묶인 시각 조건": (
        "오늘 20시 이후 영화",
        today_sql("(s.starts_at >= '2026-11-17 20:00' OR s.starts_at < '2026-11-17 10:00')"),
    ),
    "날짜가 다른 시각(내일 20시인데 답의 날짜는 오늘)": (
        "오늘 20시 이후 영화",
        today_sql("s.starts_at >= '2026-11-18 20:00'"),
    ),
    "자정 넘김 표현(다음 날 01시 이전)": (
        "오늘 새벽 1시 이전 영화",
        today_sql("s.starts_at <= '2026-11-18 01:00'"),
    ),
    "초까지 적은 시각": (
        "오늘 20시 이후 영화",
        today_sql("s.starts_at >= '2026-11-17 20:00:00'"),
    ),
}  # fmt: skip


@pytest.mark.parametrize("case", NO_CONTEXT_FOR_TIME)
def test_time_condition_that_cannot_be_confirmed_leaves_no_context(case):
    question, sql = NO_CONTEXT_FOR_TIME[case]
    service, _ = make(ScriptedLlm(run_sql(sql)))
    body = ask(service, question)
    assert body["path"] == "NL2SQL", body["answer"]
    assert body["context"] is None


def test_from_now_literal_alone_is_not_a_time_condition():
    """모든 회차 SQL에 붙는 「지금 이후」는 시각 조건이 아니다 — 문맥은 남고 시각은 없다."""
    service, _ = make(ScriptedLlm(run_sql(today_sql("t.name = '건대입구'"))))
    body = ask(service, "오늘 건대입구 영화 뭐 있어?")
    assert body["context"]["label"] == "오늘 11/17(화) · 건대입구"
    assert body["context"]["time"] is None and body["context"]["timeDirection"] is None
    picked = pick(service, body, HANGANG)
    assert picked["presetId"] == "ctx_movie_date_theater"
    assert picked["context"]["inherited"] == ["date", "theater"]


def test_preset_time_is_not_kept_when_the_question_says_the_other_direction():
    """프리셋 SQL은 늘 「이후」다 — 질문이 「이전」이면 잘못 고른 것, 문맥을 남기지 않는다."""
    service, _ = make(ScriptedLlm(after("20:00")))
    body = ask(service, "오늘 20시 이전 영화 알려줘")
    assert body["path"] == "PRESET" and body["context"] is None


def test_time_is_dropped_with_the_date_after_midnight():
    """날짜가 지났으면 시각도 버린다 — 어제의 「22시 이후」가 오늘에 붙지 않는다."""
    llm = ScriptedLlm(after("22:00"), movie_everywhere())
    service, _ = make(llm)
    first = ask(service, "오늘 22시 이후 영화 알려줘")
    # 이어받을 것이 없다(날짜도 시각도) → 평소 경로(Claude)
    picked = pick(service, first, now=NOW + timedelta(days=1))
    assert picked["llm"]["calls"] == 1 and picked["presetId"] == "movie_theaters"
    assert picked["context"]["inherited"] == [] and picked["context"]["time"] is None


def test_time_is_dropped_with_the_date_but_the_theater_is_kept():
    llm = ScriptedLlm(run_sql(today_sql("s.starts_at >= '2026-11-17 20:30'", theater=True)))
    service, _ = make(llm)
    first = ask(service, "오늘 건대입구 20시 30분 이후 영화 뭐 있어?")
    picked = pick(service, first, now=NOW + timedelta(hours=5))  # 자정을 넘겨 11/18 00:40
    assert picked["presetId"] == "ctx_movie_theater" and picked["llm"]["calls"] == 0
    assert picked["context"]["inherited"] == ["theater"]
    assert picked["context"]["label"] == "건대입구"
    assert picked["context"]["time"] is None and picked["context"]["timeDirection"] is None
    assert picked["rows"] == [["2026-11-18 14:00"]]  # 건대입구의 철의 심장 전부(시각 조건 없음)


def test_time_that_has_already_passed_today_is_kept():
    """오늘의 지난 시각이어도 그대로 둔다 — 틀에 「지금 이후」가 함께 걸려 있다."""
    service, _ = make(ScriptedLlm(after("20:00")))
    first = ask(service, "오늘 20시 이후 영화 알려줘")
    picked = pick(service, first, HANGANG, now=NOW + timedelta(hours=2))  # 21:40
    assert picked["context"]["inherited"] == ["date", "time"]
    assert picked["rows"] == [["강남", "2026-11-18 00:30"]]  # 21:00 은 이미 시작했다


TIMED_ENTRY = {
    "id": "k7f2a1", "date": "2026-11-17", "theater": "건대입구", "movie": None,
    "time": "20:00", "timeDirection": "after",
}  # fmt: skip


def test_plan_inherits_the_time_only_together_with_the_date():
    theaters, movies = ["건대입구", "강남"], ["철의 심장"]
    plan = ctx.plan(TIMED_ENTRY, BUTTON, NOW, theaters, movies)
    assert plan.template.preset_id == "ctx_movie_date_theater_after"
    assert plan.inherited == ("date", "theater", "time")
    assert plan.values == {
        "movie": "철의 심장", "date": "2026-11-17", "theater": "건대입구", "time": "20:00",
    }  # fmt: skip
    assert plan.entry_values["timeDirection"] == "after"
    # 새 메시지가 날짜를 말했다 — 그 시각은 앞 답의 날짜에 붙은 조건이라 버린다
    said = ctx.plan(TIMED_ENTRY, "내일 철의 심장 어디서 해?", NOW, theaters, movies)
    assert said.template.preset_id == "ctx_movie_date_theater" and said.inherited == ("theater",)
    assert "time" not in said.values and said.entry_values["timeDirection"] is None
    # 새 메시지가 시각을 말했다 — 틀이 담지 못한다(지금처럼 평소 경로)
    assert ctx.plan(TIMED_ENTRY, "철의 심장 21시 이후 회차", NOW, theaters, movies) is None


@pytest.mark.parametrize(
    "broken",
    [
        {"time": "25:00"},
        {"time": "20시"},
        {"time": "20:00' OR 1=1 --"},
        {"time": 2000},
        {"timeDirection": "sideways"},
        {"timeDirection": ">="},
        {"timeDirection": None},
        {"time": None},
    ],
)
def test_broken_time_in_the_session_is_not_inherited(broken):
    """세션 값이 깨졌으면(모양이 다른 시각·모르는 방향) 시각 없이 날짜·극장만 이어받는다."""
    plan = ctx.plan(TIMED_ENTRY | broken, BUTTON, NOW, ["건대입구"], ["철의 심장"])
    assert plan.template.preset_id == "ctx_movie_date_theater"
    assert plan.inherited == ("date", "theater") and "time" not in plan.values


def test_time_without_a_date_is_never_stored_or_shown():
    entry = ctx.new_entry([], {"theater": "건대입구", "time": "20:00", "timeDirection": "after"})
    assert entry["time"] is None and entry["timeDirection"] is None
    assert ctx.response(entry, (), NOW.date())["label"] == "건대입구"


@pytest.mark.parametrize(
    ("said", "meaning", "expected"),
    [
        (["20:00"], ("after",), {"time": "20:00", "timeDirection": "after"}),
        (["21:00", "20:00"], ("after", "most"), {"time": "20:00", "timeDirection": "after"}),
        (["21:00"], ("after",), None),
        ([], ("after",), None),
        (["20:00"], (), None),
        (["20:00"], ("before",), None),
        (["20:00"], ("after", "before"), None),
    ],
)
def test_confirmed_time_needs_the_said_time_and_the_same_direction(said, meaning, expected):
    bound = TimeBound("2026-11-17", "20:00", "after")
    assert ctx.confirmed_time(bound, said, meaning) == expected


def test_time_labels():
    assert ctx.time_label("20:00", "after") == "20시 이후"
    assert ctx.time_label("09:05", "before") == "9시 5분 이전"
    entry = TIMED_ENTRY | {"movie": "철의 심장"}
    assert ctx.label(entry, ("date", "theater", "time"), NOW.date()) == (
        "오늘 11/17(화) · 건대입구 · 20시 이후"
    )
    assert ctx.response(entry, (), NOW.date())["label"] == (
        "오늘 11/17(화) · 건대입구 · 20시 이후 · 철의 심장"
    )


def test_typed_question_does_not_use_the_stored_time():
    """세션에 「22시 이후」가 남아 있어도 continueFrom 없는 질문은 쓰지 않는다."""
    llm = ScriptedLlm(after("22:00"), movie_everywhere())
    service, _ = make(llm)
    first = ask(service, "오늘 22시 이후 영화 알려줘")
    typed = ask(service, BUTTON, first["sessionId"])
    assert typed["rowCount"] == 4 and typed["llm"]["calls"] == 1
    assert typed["context"]["inherited"] == [] and typed["context"]["time"] is None


# --- 좌석 잡기(3.4.19)와 한 요청에서 만날 때: 좌석 잡기가 먼저, 문맥은 건드리지 않는다 ------


def _hold_ask(service, message, session_id=None, continue_from=None):
    return asyncio.run(
        service.answer(
            message,
            now=NOW,
            user_id=USER,
            session_id=session_id,
            continue_from=continue_from,
            authorization=seat.TOKEN,
        )
    )


def test_seat_hold_confirm_with_continue_from_is_handled_by_seat_hold_and_keeps_the_context():
    """확인 버튼 문장에 continueFrom 이 실려 와도 선점 1회 · 이어받지 않음 · ctx 는 그대로."""
    llm = ScriptedLlm(run_sql(today_sql("t.name = '건대입구'")), seat.hold_tool())
    service, api, valkey = seat.make(llm)
    first = _hold_ask(service, "오늘 건대입구 영화 뭐 있어?")
    context_id = first["context"]["id"]
    asked = _hold_ask(service, seat.QUESTION, first["sessionId"])
    assert asked["path"] == "HOLD_CONFIRM" and asked["context"] is None
    before = json.loads(asyncio.run(valkey.get(session_key(USER, first["sessionId"]))))
    held = _hold_ask(service, seat.yes(asked), asked["sessionId"], continue_from=context_id)
    assert held["path"] == "HOLD_RESULT" and held["seatHold"]["bookingId"] == 9876
    assert held["context"] is None and held["llm"]["calls"] == 0 and len(api.posts) == 1
    # 세션(직전 질문·SQL·ctx)은 좌석 잡기가 바꾸지 않았다
    after_hold = json.loads(asyncio.run(valkey.get(session_key(USER, first["sessionId"]))))
    assert after_hold == before and [e["id"] for e in after_hold["ctx"]] == [context_id]
    # 그 뒤 처음 답의 영화 버튼은 여전히 그 답의 조건을 이어받는다
    picked = _hold_ask(service, HANGANG, first["sessionId"], continue_from=context_id)
    assert picked["rows"] == [["2026-11-17 21:00"]] and picked["seatHold"] is None
    assert picked["context"]["inherited"] == ["date", "theater"] and len(llm.requests) == 2


def test_seat_hold_button_message_with_continue_from_is_not_continued_without_a_pending_action():
    """대기 행동이 없는 「좌석 잡기 #…」에 continueFrom 이 실려 와도 좌석 잡기 안내가 답한다."""
    llm = ScriptedLlm(run_sql(today_sql("t.name = '건대입구'")))
    service, api, _ = seat.make(llm)
    first = _hold_ask(service, "오늘 건대입구 영화 뭐 있어?")
    body = _hold_ask(
        service, "좌석 잡기 #abc123", first["sessionId"], continue_from=first["context"]["id"]
    )
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == "NOT_CONFIRMED"
    assert body["context"] is None and body["rows"] is None and api.posts == []
    assert body["llm"]["calls"] == 0 and len(llm.requests) == 1
