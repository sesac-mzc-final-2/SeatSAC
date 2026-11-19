"""문맥 이어받기 1단계(3.4.22): 답 아래 영화 버튼이 앞 답의 날짜·극장을 잃지 않는다.

가짜 Claude · fakeredis · 값을 아는 스냅샷(sql_fixtures: 오늘 11/17(화) 19:40, 극장 건대입구·강남).
철의 심장 회차: 11/17 강남 22:30 · 11/18 건대입구 14:00 · 11/19 강남 09:30 · 11/21 강남 15:00
"""

import asyncio
import json
import re
import uuid
from datetime import timedelta

import fakeredis
import pytest
from fastapi.testclient import TestClient

from seatsac_ai import context as ctx
from seatsac_ai.cache.store import session_key
from seatsac_ai.cache.warm import preset_items, warm
from seatsac_ai.config import Settings
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.main import create_app
from seatsac_ai.resources import context_templates, presets
from seatsac_ai.sql.conditions import analyze
from seatsac_ai.sql.validator import validate

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
    asyncio.run(warm(cache, preset_items(presets() + context_templates())))
    first = ask(service, "건대입구 내일 영화 뭐해?")
    hit = pick(service, first)
    assert hit["path"] == "CACHE_EXACT" and hit["llm"]["calls"] == 0
    assert hit["presetId"] == "ctx_movie_date_theater" and "'2026-11-18'" in hit["sql"]

    async def drop_cache():
        for key in await valkey.keys("ai:exact:*"):
            await valkey.delete(key)

    asyncio.run(drop_cache())  # 캐시 수명이 지났거나 ai Valkey가 재부팅됐다
    miss = pick(service, first)
    assert miss["path"] == "PRESET" and miss["llm"]["calls"] == 0
    assert miss["rows"] == hit["rows"] == [["2026-11-18 14:00"]]
    assert miss["context"]["inherited"] == hit["context"]["inherited"] == ["date", "theater"]
    assert len(llm.requests) == 1


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
    assert session["ctx"] == [
        {"id": first["context"]["id"], "date": "2026-11-18", "theater": "건대입구", "movie": None},
        {
            "id": picked["context"]["id"],
            "date": "2026-11-18",
            "theater": "건대입구",
            "movie": "철의 심장",
        },
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
