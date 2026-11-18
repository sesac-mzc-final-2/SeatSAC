"""실사용 점검(11/18, 가상 사용자 27문항)에서 나온 4가지 — 다시 생기지 않게.

1. 의미 캐시가 새 질문에만 있는 말(없는 지점·새 조건)을 버리고 앞 질문의 SQL로 답했다
2. 없는 지점·영화를 물으면 "결과 없음"만 나왔다
3. "같은 조건으로 …"·"부산은?"을 후속 질문으로 못 알아봤다
4. 편성이 없는 날짜까지 물어도 알리지 않았다
"""

from datetime import datetime

import pytest

from seatsac_ai.cache.slots import extra_words, is_followup
from seatsac_ai.llm import prompt
from seatsac_ai.snapshot.mapper import KST

from .llm_fakes import ScriptedLlm, tool
from .test_cache_flow import FakeEmbedder, ask, make

BASE = "SELECT m.title AS movie_title, t.name AS theater_name, s.starts_at FROM screening s "
JOINS = "JOIN movie m ON m.movie_id = s.movie_id JOIN theater t ON t.theater_id = s.theater_id "


def sql(where):
    return BASE + JOINS + "WHERE " + where + " AND s.starts_at > '2026-11-17 19:40'"


# --- 1. 의미 캐시 -------------------------------------------------------------


@pytest.mark.parametrize(
    ("new", "cached", "extra"),
    [
        ("내일 홍대 잔여석 많은 영화 {n}개", "내일 잔여석 많은 영화 {n}개", ("홍대",)),
        ("내일 잠실에서 잔여석 많은 영화 {n}개", "내일 잔여석 많은 영화 {n}개", ("잠실",)),
        (
            "오늘 리클라이너관 자리 많이 남은 회차 {n}개",
            "오늘 자리 많이 남은 회차 {n}개",
            ("리클라이너",),
        ),
        ("오늘 {time} 이후에 시작하는 액션 영화", "오늘 {time} 이후에 시작하는 영화", ("액션",)),
        # 말투·부탁 꼬리·뜻이 같은 말만 다르면 새 낱말이 아니다
        ("오늘 {time} 넘어서 하는 영화 보여줘", "오늘 {time} 이후에 시작하는 영화 알려줘", ()),
        (
            "오늘 {time} 이후에 하는 영화 좀 알려주세요",
            "오늘 {time} 이후에 시작하는 영화 알려줘",
            (),
        ),
        (
            "영화 오늘 {time} 이후에 시작하는 거 뭐 있어",
            "오늘 {time} 이후에 시작하는 영화 알려줘",
            (),
        ),
    ],
)
def test_extra_words(new, cached, extra):
    assert extra_words(new, cached) == extra


def test_question_with_a_new_word_does_not_reuse_the_cached_sql():
    cached = "내일 잔여석 많은 영화 {n}개"
    new = "내일 홍대 잔여석 많은 영화 {n}개"
    embedder = FakeEmbedder({cached: [1.0, 0.0, 0.0], new: [0.99, 0.1, 0.0]})  # 코사인 약 0.99
    seats = sql("s.business_date = '2026-11-18'") + " ORDER BY s.remaining_seats DESC LIMIT 3"
    hongdae = sql("s.business_date = '2026-11-18' AND t.name LIKE '%홍대%'") + " LIMIT 3"
    llm = ScriptedLlm(
        tool("run_sql", sql=seats, explanation="내일 잔여석 많은 영화 3개"),
        tool("run_sql", sql=hongdae, explanation="내일 홍대 잔여석 많은 영화 3개"),
    )
    service, _ = make(llm, embedder)
    assert ask(service, "내일 잔여석 많은 영화 3개")["rowCount"] > 0
    second = ask(service, "내일 홍대 잔여석 많은 영화 3개")
    assert second["path"] == "NL2SQL" and second["llm"]["calls"] == 1
    assert second["rowCount"] == 0 and "'홍대' 지점은 없습니다" in second["answer"]


# --- 2. 없는 지점·영화 --------------------------------------------------------


def test_unknown_theater_is_named_with_the_real_ones_and_not_cached():
    reply = tool("run_sql", sql=sql("t.name LIKE '%홍대%'"), explanation="홍대 회차")
    llm = ScriptedLlm(reply, reply)
    service, _ = make(llm)
    first = ask(service, "홍대에서 하는 영화 알려줘")
    assert first["path"] == "NL2SQL" and first["rowCount"] == 0
    assert first["answer"].startswith("'홍대' 지점은 없습니다. 예매할 수 있는 지점: 강남·건대입구.")
    assert "기준)" in first["answer"]
    ask(service, "홍대에서 하는 영화 알려줘")
    assert len(llm.requests) == 2  # 캐시하지 않았다


def test_unknown_movie_is_named():
    reply = tool("run_sql", sql=sql("m.title = '주토피아'"), explanation="주토피아 회차")
    service, _ = make(ScriptedLlm(reply))
    answer = ask(service, "주토피아 어디서 해?")["answer"]
    assert "'주토피아'은(는) 지금 상영 정보에 없는 영화입니다." in answer


def test_known_names_with_no_rows_keep_the_plain_empty_answer():
    # 지점도 영화도 있는데 조건에 맞는 회차가 없을 뿐 — "없는 지점"이라고 하면 거짓말
    where = "t.name = '건대입구' AND m.title LIKE '%철의 심장%' AND s.business_date = '2026-11-17'"
    service, _ = make(
        ScriptedLlm(tool("run_sql", sql=sql(where), explanation="건대입구 철의 심장"))
    )
    answer = ask(service, "건대입구 철의 심장 오늘 회차")["answer"]
    assert "조건에 맞는 결과가 없습니다" in answer and "없는 영화" not in answer


# --- 3. 후속 질문 -------------------------------------------------------------


@pytest.mark.parametrize(
    "question",
    [
        "같은 조건으로 건대입구는 어때? 5개로 늘려서",
        "부산은?",
        "영등포도 알려줘",
        "이번에는 월드타워로",
    ],
)
def test_followups_that_were_missed(question):
    assert is_followup(question)


@pytest.mark.parametrize(
    "question",
    [
        "오늘 20시 이후에 시작하는 영화 알려줘",
        "내일 건대입구 편성표 보여줘",
        "상영 중인 영화는 뭐가 있어?",
    ],
)
def test_standalone_questions_are_not_followups(question):
    assert not is_followup(question)


# --- 4. 편성이 없는 날짜 ------------------------------------------------------


def test_asking_past_the_last_scheduled_day_says_so():
    # 픽스처의 마지막 영업일은 11/21
    weekend = sql("s.business_date IN ('2026-11-21', '2026-11-22')")
    service, _ = make(ScriptedLlm(tool("run_sql", sql=weekend, explanation="이번 주말 회차")))
    answer = ask(service, "이번 주말 회차")["answer"]
    assert "1건입니다. 회차 정보는 11월 21일까지만 있어 그 뒤 날짜는 빠져 있습니다." in answer


def test_asking_inside_the_range_adds_nothing():
    inside = sql("s.business_date = '2026-11-21'")
    service, _ = make(ScriptedLlm(tool("run_sql", sql=inside, explanation="21일 회차")))
    assert "까지만" not in ask(service, "21일 회차")["answer"]


def test_claude_is_told_the_data_range():
    llm = ScriptedLlm(tool("out_of_scope", reason="x"))
    service, _ = make(llm)
    ask(service, "아무 질문")
    sent = llm.requests[0]["messages"][0]["content"]
    assert "회차 데이터: 2026-11-17 ~ 2026-11-21" in sent


def test_user_text_without_range_is_unchanged():
    now = datetime(2026, 11, 17, 19, 40, tzinfo=KST)
    assert "회차 데이터" not in prompt.user_text("질문", now)


def test_english_guesses_for_an_unknown_movie_are_not_shown():
    where = "(m.title LIKE '%주토피아%' OR m.title_en LIKE '%Zootopia%')"
    service, _ = make(ScriptedLlm(tool("run_sql", sql=sql(where), explanation="주토피아 회차")))
    answer = ask(service, "주토피아 아직 해?")["answer"]
    assert answer.startswith("'주토피아'은(는) 지금 상영 정보에 없는 영화입니다.")


# --- 5. 표에 내부 칸이 보이지 않게 --------------------------------------------


def test_seat_source_column_is_dropped_and_unsure_seats_are_blank():
    query = (
        "SELECT s.starts_at, s.remaining_seats, s.remaining_seats_source FROM screening s "
        "WHERE s.business_date = '2026-11-17' AND s.starts_at > '2026-11-17 19:40' "
        "ORDER BY s.starts_at"
    )
    service, _ = make(ScriptedLlm(tool("run_sql", sql=query, explanation="오늘 남은 회차")))
    body = ask(service, "오늘 남은 회차 잔여석")
    assert [c["name"] for c in body["columns"]] == ["starts_at", "remaining_seats"]
    seats = dict(body["rows"])
    assert seats["2026-11-17 22:30"] is None  # 픽스처 5번 회차는 TOTAL
    assert seats["2026-11-17 20:00"] == 30
