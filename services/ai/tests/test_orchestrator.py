"""질문 처리 순서: 가짜 Claude가 고른 도구에 따라 경로·재요청·거부가 맞게 나오는지."""

import asyncio
import logging
from datetime import date, datetime

import pytest

from seatsac_ai.common.errors import ApiError
from seatsac_ai.common.metrics import (
    AI_UNMET_DEMAND,
    UNMET_CATEGORIES,
    UNMET_UNKNOWN,
    init_label_series,
)
from seatsac_ai.config import Settings
from seatsac_ai.llm import orchestrator
from seatsac_ai.llm.client import LlmUnavailable
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.snapshot.fresh import Fresh
from seatsac_ai.snapshot.mapper import KST
from seatsac_ai.snapshot.store import SnapshotNotReady, SnapshotStore

from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store

NOW = datetime(2026, 11, 17, 19, 40, tzinfo=KST)
CONTRACT_KEYS = {
    "answer", "path", "columns", "rows", "rowCount", "truncated", "sql",
    "presetId", "asOf", "llm", "latencyMs", "rejection", "choices",
}  # fmt: skip
AFTER_20 = (
    "SELECT m.title AS movie_title, s.starts_at FROM screening s "
    "JOIN movie m ON m.movie_id = s.movie_id "
    "WHERE s.business_date = '2026-11-17' AND s.starts_at >= '2026-11-17 20:00' "
    "AND s.starts_at > '2026-11-17 19:40' ORDER BY s.starts_at"
)


def ask(llm, question="질문", store=None, **settings):
    service = ChatService(store or loaded_store(), llm, Settings(_env_file=None, **settings))
    return asyncio.run(service.answer(question, now=NOW))


def test_preset_path():
    llm = ScriptedLlm(tool("run_preset", presetId="today_after_time", params={"time": "20:00"}))
    body = ask(llm, "오늘 20시 이후 영화")
    assert set(body) == CONTRACT_KEYS
    assert body["path"] == "PRESET" and body["presetId"] == "today_after_time"
    assert body["rowCount"] == 4 and body["sql"] is None
    assert body["llm"]["calls"] == 1 and body["llm"]["cacheReadTokens"] == 3800
    # 버튼 제목의 "○시"는 답 문장에서 실제 시각으로 채운다
    assert body["answer"].startswith("오늘 20시 이후 회차: 4건입니다.")


def test_preset_answer_shows_minutes_when_not_on_the_hour():
    llm = ScriptedLlm(tool("run_preset", presetId="today_after_time", params={"time": "20:30"}))
    assert ask(llm, "오늘 20시 30분 이후 영화")["answer"].startswith("오늘 20시 30분 이후 회차:")


def test_view_grade_preset_returns_only_that_grade():
    # 4.2.3 측정(q18·q19): 등급을 묻는데 전체 목록이 나오던 것 — 빈칸 grade 로 거른다
    llm = ScriptedLlm(tool("run_preset", presetId="movies_by_view_grade", params={"grade": "ALL"}))
    body = ask(llm, "전체 관람가 영화 알려줘")
    assert body["path"] == "PRESET" and body["rowCount"] == 1
    assert {row[0] for row in body["rows"]} == {"ALL"}


def test_view_grade_preset_rejects_an_unknown_grade():
    llm = ScriptedLlm(
        tool("run_preset", presetId="movies_by_view_grade", params={"grade": "18"}),
        tool("run_preset", presetId="movies_by_view_grade", params={"grade": "19"}),
    )
    body = ask(llm, "청소년 관람불가 영화 뭐 있어?")
    assert body["llm"]["calls"] == 2 and {row[0] for row in body["rows"]} <= {"19"}


def test_nl2sql_path_returns_the_sql_that_ran():
    body = ask(ScriptedLlm(tool("run_sql", sql=AFTER_20, explanation="오늘 20시 이후 회차")))
    assert body["path"] == "NL2SQL" and body["rowCount"] == 4
    assert body["sql"].endswith("LIMIT 500")  # 검증기가 붙인 LIMIT 까지 보여 준다
    assert body["answer"].startswith("오늘 20시 이후 회차: 4건입니다.")


def test_out_of_scope():
    body = ask(ScriptedLlm(tool("out_of_scope", reason="날씨")), "내일 날씨 어때?")
    assert body["path"] == "OUT_OF_SCOPE" and body["rows"] is None and body["llm"]["calls"] == 1


def _unmet(category: str) -> float:
    return AI_UNMET_DEMAND.labels(category)._value.get()


def _unmet_logs(caplog) -> list[dict]:
    return [r.fields for r in caplog.records if r.getMessage() == "unmet demand"]


@pytest.mark.parametrize("category", ["action_request", "site_feature", "unrelated"])
def test_out_of_scope_counts_unmet_demand_by_category(category):
    before = _unmet(category)
    ask(ScriptedLlm(tool("out_of_scope", category=category, reason="요약")), "아무 질문")
    assert _unmet(category) == before + 1


@pytest.mark.parametrize("given", [{"category": "주차 문의"}, {"category": 3}, {}])
def test_unmet_demand_category_outside_the_enum_becomes_unknown(given):
    # Claude가 적은 글자를 그대로 라벨로 쓰면 시계열이 끝없이 늘어난다
    before = _unmet("unknown")
    ask(ScriptedLlm(tool("out_of_scope", reason="x", **given)), "주차 돼?")
    assert _unmet("unknown") == before + 1
    init_label_series()  # 다른 테스트의 import 순서에 기대지 않는다
    assert {s.labels["category"] for s in AI_UNMET_DEMAND.collect()[0].samples} == {
        "action_request", "site_feature", "unrelated", "unknown",
    }  # fmt: skip


def test_chitchat_is_neither_counted_nor_logged(caplog):
    # 인사·잡담·욕설은 수요가 아니다 — 답은 OUT_OF_SCOPE 그대로, 기록만 하지 않는다
    before = {c: _unmet(c) for c in (*UNMET_CATEGORIES, UNMET_UNKNOWN)}
    llm = ScriptedLlm(tool("out_of_scope", category="chitchat", reason="인사"))
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        body = ask(llm, "안녕")
    assert body["path"] == "OUT_OF_SCOPE" and set(body) == CONTRACT_KEYS
    assert {c: _unmet(c) for c in before} == before and _unmet_logs(caplog) == []
    assert "chitchat" not in {s.labels["category"] for s in AI_UNMET_DEMAND.collect()[0].samples}


def test_unmet_demand_log_has_summary_but_not_the_question(caplog):
    question = "010-1234-5678 로 건대입구 주차 요금 알려줘"
    long_reason = "주차 요금 안내  \n" + "가" * 200
    llm = ScriptedLlm(tool("out_of_scope", category="site_feature", reason=long_reason))
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        body = ask(llm, question)
    assert set(body) == CONTRACT_KEYS  # 응답 계약은 그대로다(category·reason 은 응답에 없다)
    (fields,) = _unmet_logs(caplog)
    assert set(fields) == {"category", "reason", "questionHash", "questionLength"}
    assert fields["category"] == "site_feature"
    assert len(fields["reason"]) == 120 and fields["reason"].startswith("주차 요금 안내 가")
    assert fields["questionLength"] == len(question) and len(fields["questionHash"]) == 16
    for record in caplog.records:  # 어느 로그 줄에도 질문 원문이 없다
        assert question not in record.getMessage()
        assert question not in str(getattr(record, "fields", ""))


def _unmet_reason_fields(caplog, question: str, reason: str) -> dict:
    llm = ScriptedLlm(tool("out_of_scope", category="site_feature", reason=reason))
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        ask(llm, question)
    (fields,) = _unmet_logs(caplog)
    return fields


@pytest.mark.parametrize(
    "reason",
    [
        "건대입구 주차 요금 얼마야?",  # 그대로
        "사용자 질문: 건대입구  주차요금 얼마야!!",  # 공백·문장부호만 바꿔 옮김
    ],
)
def test_reason_that_copies_the_question_is_dropped(caplog, reason):
    before = _unmet("site_feature")
    fields = _unmet_reason_fields(caplog, "건대입구 주차 요금 얼마야?", reason)
    assert set(fields) == {"category", "reason", "reasonDropped", "questionHash", "questionLength"}
    assert fields["reason"] is None and fields["reasonDropped"] is True
    assert fields["category"] == "site_feature" and _unmet("site_feature") == before + 1


def test_long_question_copied_in_part_is_dropped(caplog):
    # 질문이 길어도 20자가 그대로 겹치면 버린다(60% 기준만 쓰면 긴 질문에서 발동하지 않는다)
    question = "".join(chr(0xAC00 + i * 7) for i in range(220))
    fields = _unmet_reason_fields(caplog, question, "요약: " + question[:40])
    assert fields["reason"] is None and fields["reasonDropped"] is True


@pytest.mark.parametrize(
    ("reason", "kept"),
    [
        ("010-1234-5678 hong@example.com 주차 요금 안내", "[숫자] [이메일] 주차 요금 안내"),
        ("01012345678 주차 요금 안내", "[숫자] 주차 요금 안내"),
        ("2관 20시 회차 할인 문의", "2관 20시 회차 할인 문의"),  # 숫자 한두 개는 그대로
    ],
)
def test_phone_numbers_and_emails_in_reason_are_masked(caplog, reason, kept):
    fields = _unmet_reason_fields(caplog, "주차 요금 알려줘", reason)
    assert fields["reason"] == kept and "reasonDropped" not in fields
    # questionHash 는 임의 16진수라 우연히 010 이 나올 수 있어 뺀다
    rest = str({k: v for k, v in fields.items() if k != "questionHash"})
    assert "010" not in rest and "@" not in rest


@pytest.mark.parametrize("reason", [5, None, ["주차"], "", "  \n "])
def test_reason_that_is_not_text_or_is_empty_becomes_none(caplog, reason):
    fields = _unmet_reason_fields(caplog, "주차 돼?", reason)
    assert fields["reason"] is None and "reasonDropped" not in fields


def test_category_is_matched_ignoring_case_and_spaces(caplog):
    before = {c: _unmet(c) for c in (*UNMET_CATEGORIES, UNMET_UNKNOWN)}
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        ask(ScriptedLlm(tool("out_of_scope", category="CHITCHAT", reason="인사")), "안녕")
        assert _unmet_logs(caplog) == [] and {c: _unmet(c) for c in before} == before
        ask(ScriptedLlm(tool("out_of_scope", category=" Site_Feature ", reason="주차")), "주차?")
    assert _unmet("site_feature") == before["site_feature"] + 1
    assert _unmet("unknown") == before["unknown"]


def test_chitchat_with_copied_reason_leaves_nothing(caplog):
    question = "야 이 멍청한 챗봇아 대답 좀 해봐"
    llm = ScriptedLlm(tool("out_of_scope", category="chitchat", reason=question))
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        ask(llm, question)
    assert _unmet_logs(caplog) == []
    for record in caplog.records:
        assert question not in record.getMessage()
        assert question not in str(getattr(record, "fields", ""))


def test_out_of_scope_after_a_nudge_is_recorded_once(caplog):
    before = _unmet("unrelated")
    llm = ScriptedLlm(text_only(), tool("out_of_scope", category="unrelated", reason="날씨 안내"))
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        body = ask(llm, "내일 날씨 어때?")
    assert body["path"] == "OUT_OF_SCOPE" and body["llm"]["calls"] == 2
    assert _unmet("unrelated") == before + 1 and len(_unmet_logs(caplog)) == 1


def test_recording_failure_does_not_break_the_answer(caplog, monkeypatch):
    def boom(question, reason):
        raise RuntimeError(f"{question} {reason}")

    monkeypatch.setattr(orchestrator, "_copies_question", boom)
    llm = ScriptedLlm(tool("out_of_scope", category="site_feature", reason="주차 요금 안내"))
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        body = ask(llm, "건대입구 주차 요금 얼마야?")
    assert body["path"] == "OUT_OF_SCOPE" and _unmet_logs(caplog) == []
    (warning,) = [r for r in caplog.records if "기록 실패" in r.getMessage()]
    assert "RuntimeError" in warning.getMessage() and "주차" not in warning.getMessage()


def test_tool_input_that_is_not_a_dict_counts_as_unknown(caplog):
    before = _unmet("unknown")
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        orchestrator._record_unmet("주차 돼?", ["category", "site_feature"])
    assert _unmet("unknown") == before + 1
    (fields,) = _unmet_logs(caplog)
    assert fields["category"] == "unknown" and fields["reason"] is None


def test_summary_reason_is_kept(caplog):
    fields = _unmet_reason_fields(caplog, "건대입구 주차 요금 얼마야?", "극장 주차 요금 안내")
    assert fields["reason"] == "극장 주차 요금 안내" and "reasonDropped" not in fields


def test_short_question_never_drops_the_reason(caplog):
    # 겹침 기준의 하한이 8자라, 짧은 질문은 요약과 같아도 남긴다(의도)
    fields = _unmet_reason_fields(caplog, "날씨", "날씨")
    assert fields["reason"] == "날씨" and "reasonDropped" not in fields


def test_other_paths_do_not_count_unmet_demand(caplog):
    before = {c: _unmet(c) for c in (*UNMET_CATEGORIES, UNMET_UNKNOWN)}
    llm = ScriptedLlm(tool("run_preset", presetId="today_after_time", params={"time": "20:00"}))
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        ask(llm, "오늘 20시 이후 영화")
    assert {c: _unmet(c) for c in before} == before and _unmet_logs(caplog) == []


def test_rejected_sql_is_retried_once_with_the_reason():
    llm = ScriptedLlm(
        tool("run_sql", sql="SELECT * FROM snapshot_meta", explanation="x"),
        tool("run_sql", sql=AFTER_20, explanation="오늘 20시 이후 회차"),
    )
    body = ask(llm)
    assert body["path"] == "NL2SQL" and body["llm"]["calls"] == 2
    feedback = llm.requests[1]["messages"][-1]["content"][0]
    assert feedback["type"] == "tool_result" and feedback["is_error"]
    assert "FORBIDDEN_OBJECT" in feedback["content"]


def test_rejected_after_all_calls_are_used():
    bad = tool("run_sql", sql="SELECT * FROM snapshot_meta", explanation="x")
    body = ask(ScriptedLlm(bad, bad, bad))
    assert body["path"] == "REJECTED" and body["llm"]["calls"] == 3
    assert body["rejection"]["reason"] == "FORBIDDEN_OBJECT"
    assert body["rejection"]["object"] == "snapshot_meta"
    assert body["sql"] == "SELECT * FROM snapshot_meta"  # 계약: REJECTED면 거부된 SQL


def test_sql_error_is_retried():
    llm = ScriptedLlm(
        tool("run_sql", sql="SELECT nope FROM movie", explanation="x"),
        tool("run_sql", sql="SELECT title FROM movie", explanation="영화 목록"),
    )
    body = ask(llm)
    assert body["path"] == "NL2SQL" and body["rowCount"] == 4
    assert "nope" in llm.requests[1]["messages"][-1]["content"][0]["content"]


def test_preset_with_missing_slot_is_retried():
    llm = ScriptedLlm(
        tool("run_preset", presetId="theater_schedule", params={"date": "2026-11-18"}),
        tool("run_preset", presetId="theater_schedule",
             params={"date": "2026-11-18", "theater": "건대입구"}),
    )  # fmt: skip
    body = ask(llm)
    assert body["path"] == "PRESET" and body["rowCount"] == 2 and body["llm"]["calls"] == 2


def test_partial_theater_name_is_matched_to_the_snapshot():
    llm = ScriptedLlm(
        tool(
            "run_preset",
            presetId="theater_schedule",
            params={"date": "2026-11-18", "theater": "건대"},
        )
    )
    assert ask(llm)["rowCount"] == 2


def test_unknown_movie_name_is_sent_back_with_choices():
    llm = ScriptedLlm(
        tool("run_preset", presetId="movie_runtime", params={"movie": "없는 영화"}),
        tool("run_preset", presetId="movie_runtime", params={"movie": "철의 심장"}),
    )
    body = ask(llm)
    assert body["rows"] == [["철의 심장", 130]]  # 러닝타임 없음 → 회차 간격으로 대신
    assert "별의 항해" in llm.requests[1]["messages"][-1]["content"][0]["content"]


def test_runtime_uses_real_running_time_when_present():
    llm = ScriptedLlm(tool("run_preset", presetId="movie_runtime", params={"movie": "별의 항해"}))
    assert ask(llm)["rows"] == [["별의 항해", 125]]


def test_today_and_now_are_given_to_claude_outside_the_cached_system():
    llm = ScriptedLlm(tool("out_of_scope", reason="x"))
    ask(llm, "오늘 뭐 해?")
    req = llm.requests[0]
    assert "오늘: 2026-11-17(화) · 지금: 2026-11-17 19:40" in req["messages"][0]["content"]
    assert req["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert "2026-11-17 19:40 (KST)" not in req["system"][0]["text"]
    assert [t["name"] for t in req["tools"]] == [
        "run_preset",
        "run_sql",
        "ask_user",
        "out_of_scope",
    ]


def test_prompt_cache_can_be_turned_off():
    llm = ScriptedLlm(tool("out_of_scope", reason="x"))
    ask(llm, APP_AI_PROMPT_CACHE_ENABLED=False)
    assert "cache_control" not in llm.requests[0]["system"][0]


def test_llm_failure_becomes_503():
    with pytest.raises(ApiError) as caught:
        ask(ScriptedLlm(LlmUnavailable("RATE_LIMITED")))
    assert caught.value.status == 503 and caught.value.code == "AI_LLM_UNAVAILABLE"
    assert caught.value.details[0].reason == "RATE_LIMITED"


def test_llm_timeout_is_capped_by_the_request_deadline():
    llm = ScriptedLlm(tool("out_of_scope", reason="x"))
    ask(llm, APP_AI_REQUEST_DEADLINE_SECONDS=4)
    assert llm.requests[0]["timeout"] <= 4


def test_snapshot_not_ready():
    with pytest.raises(ApiError) as caught:
        ask(ScriptedLlm(), store=SnapshotStore())
    assert caught.value.code == "AI_SNAPSHOT_NOT_READY"


def test_snapshot_becoming_unready_mid_query_is_503():
    class FlakyStore(SnapshotStore):
        def is_ready(self):
            return True  # 입구 검사는 통과시키고

        def reader(self):
            raise SnapshotNotReady("교체 중")  # 실제 질의 순간에만 실패

    llm = ScriptedLlm(tool("run_sql", sql="SELECT title FROM movie", explanation="x"))
    with pytest.raises(ApiError) as caught:
        ask(llm, store=FlakyStore())
    assert caught.value.code == "AI_SNAPSHOT_NOT_READY"


def test_screening_sql_without_started_filter_still_excludes_started():
    """data-model 6-1: Claude가 '시작한 회차 제외'를 빼먹어도 실행할 SQL에 붙여 바로 답한다."""
    no_filter = (
        "SELECT m.title AS movie_title, s.starts_at FROM screening s "
        "JOIN movie m ON m.movie_id = s.movie_id WHERE s.business_date = '2026-11-17'"
    )
    body = ask(ScriptedLlm(tool("run_sql", sql=no_filter, explanation="오늘 회차")))
    assert body["path"] == "NL2SQL" and body["llm"]["calls"] == 1
    starts = sorted(row[1] for row in body["rows"])
    assert "2026-11-17 18:00" not in starts  # 19:40 기준 이미 시작
    assert starts == ["2026-11-17 19:50", "2026-11-17 20:00", "2026-11-17 21:00",
                      "2026-11-17 22:30", "2026-11-18 00:30"]  # fmt: skip
    assert "s.starts_at > :now" in body["sql"]


def test_earlier_time_comparison_does_not_bring_back_started_screenings():
    """#93 재리뷰: 21:30에 '20시 이후'를 `>= 20:00`으로만 걸러도 20:00·21:00 회차는 빠진다."""
    after_20 = (
        "SELECT s.starts_at FROM screening s "
        "WHERE s.business_date = '2026-11-17' AND s.starts_at >= '2026-11-17 20:00'"
    )
    service = ChatService(
        loaded_store(), ScriptedLlm(tool("run_sql", sql=after_20, explanation="x")),
        Settings(_env_file=None),
    )  # fmt: skip
    at_2130 = datetime(2026, 11, 17, 21, 30, tzinfo=KST)
    body = asyncio.run(service.answer("오늘 20시 이후", now=at_2130))
    assert sorted(row[0] for row in body["rows"]) == ["2026-11-17 22:30", "2026-11-18 00:30"]


def test_started_filter_goes_into_join_on_so_left_join_keeps_its_meaning():
    """LEFT JOIN screening 은 ON 에 붙인다 — 이후 회차가 없는 영화도 행으로 남는다."""
    sql = (
        "SELECT m.title AS movie_title, count(s.screening_id) AS screening_count FROM movie m "
        "LEFT JOIN screening s ON s.movie_id = m.movie_id GROUP BY m.movie_id ORDER BY m.movie_id"
    )
    body = ask(ScriptedLlm(tool("run_sql", sql=sql, explanation="영화별 남은 회차")))
    # 19:40 이후: 별의 항해 1(18:00 제외) · 한강의 밤 2 · 고양이 탐정 2 · 철의 심장 3
    # 철의 심장은 11/19 조조 회차(픽스처 10번)까지 4
    assert [row[1] for row in body["rows"]] == [1, 2, 2, 4]


def test_sql_without_screening_does_not_need_started_filter():
    body = ask(ScriptedLlm(tool("run_sql", sql="SELECT title FROM movie", explanation="영화")))
    assert body["path"] == "NL2SQL" and body["llm"]["calls"] == 1


def test_long_explanation_is_cut_to_80_chars():
    body = ask(ScriptedLlm(tool("run_sql", sql=AFTER_20, explanation="가" * 200)))
    assert body["answer"].startswith("가" * 80 + ":")
    assert not body["answer"].startswith("가" * 81)


def test_llm_failure_after_choosing_a_preset_is_counted_as_preset():
    from prometheus_client import REGISTRY

    def errors(path):
        return REGISTRY.get_sample_value("ai_chat_total", {"path": path, "result": "error"}) or 0

    before_preset, before_nl2sql = errors("preset"), errors("nl2sql")
    llm = ScriptedLlm(
        tool("run_preset", presetId="theater_schedule", params={"date": "2026-11-18"}),  # 빈칸 부족
        LlmUnavailable("OVERLOADED"),  # 재요청에서 실패
    )
    with pytest.raises(ApiError):
        ask(llm)
    assert errors("preset") == before_preset + 1
    assert errors("nl2sql") == before_nl2sql


def test_tokens_already_spent_are_counted_even_if_a_later_call_fails():
    from prometheus_client import REGISTRY

    def tokens(kind, path):
        labels = {"kind": kind, "path": path}
        return REGISTRY.get_sample_value("ai_tokens_total", labels) or 0

    before = tokens("input", "preset")
    llm = ScriptedLlm(
        tool("run_preset", presetId="theater_schedule", params={"date": "2026-11-18"}),  # 빈칸 부족
        LlmUnavailable("OVERLOADED"),
    )
    with pytest.raises(ApiError):
        ask(llm)
    assert tokens("input", "preset") == before + 400  # 첫 호출(성공)의 input 토큰


def text_only(text="20시 이후에는 세 편이 있어요"):
    from seatsac_ai.llm.client import NO_TOOL, ToolCall, Usage

    return ToolCall("", NO_TOOL, {}, [{"type": "text", "text": text}], Usage(input=400, output=30))


def test_text_only_reply_is_asked_again_and_never_shown():
    # tool_choice=auto(claude-sonnet-5-5 는 강제 불가): 도구 없이 쓴 글은 버리고 한 번 다시 시킨다
    llm = ScriptedLlm(
        text_only(), tool("run_preset", presetId="today_after_time", params={"time": "20:00"})
    )
    body = ask(llm, "오늘 20시 이후 영화")
    assert body["path"] == "PRESET" and body["llm"]["calls"] == 2
    assert "세 편" not in body["answer"]
    nudge = llm.requests[1]["messages"]
    assert nudge[-2]["role"] == "assistant" and nudge[-1]["role"] == "user"
    assert "반드시 호출" in nudge[-1]["content"]


def test_text_only_twice_falls_back_to_out_of_scope():
    llm = ScriptedLlm(text_only(), text_only())
    body = ask(llm, "오늘 20시 이후 영화")
    assert body["path"] == "OUT_OF_SCOPE" and body["rows"] is None
    assert body["llm"]["calls"] == 2 and "세 편" not in body["answer"]


def test_text_only_fallback_is_not_unmet_demand(caplog):
    # 도구 없이 끝난 것은 모델 실패다 — 범위 안 질문이 섞이므로 수요로 세지 않는다
    before = {c: _unmet(c) for c in (*UNMET_CATEGORIES, UNMET_UNKNOWN)}
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        body = ask(ScriptedLlm(text_only(), text_only()), "오늘 20시 이후 영화")
    assert body["path"] == "OUT_OF_SCOPE"
    assert {c: _unmet(c) for c in before} == before and _unmet_logs(caplog) == []


class FakeRefresher:
    """질문 시점 갱신 가짜: 받은 범위를 적어 두고 정해 둔 결과를 돌려준다."""

    def __init__(self, fresh=None):
        self.calls = []
        self.timeouts = []
        self.fresh = fresh or Fresh(ok=True, at=datetime(2026, 11, 17, 19, 41, tzinfo=KST))

    async def ensure_fresh(self, days=None, theater_ids=None, *, timeout=None):
        self.calls.append((days, theater_ids))
        self.timeouts.append(timeout)
        return self.fresh


def _ask_with(llm, question, refresher=None, **settings):
    refresher = refresher or FakeRefresher()
    service = ChatService(
        loaded_store(), llm, Settings(_env_file=None, **settings), refresher=refresher
    )
    return asyncio.run(service.answer(question, now=NOW)), refresher


SEATS_JOINS = (
    "SELECT m.title AS movie_title, s.starts_at, s.remaining_seats FROM screening s "
    "JOIN movie m ON m.movie_id = s.movie_id JOIN theater t ON t.theater_id = s.theater_id "
)


def test_seat_question_refreshes_only_that_day_and_theater():
    sql = SEATS_JOINS + "WHERE s.business_date = '2026-11-17' AND t.name = '건대입구'"
    body, refresher = _ask_with(
        ScriptedLlm(tool("run_sql", sql=sql, explanation="x")), "오늘 건대입구 잔여석"
    )
    assert body["path"] == "NL2SQL"
    assert refresher.calls == [([date(2026, 11, 17)], [10])]  # 호출 범위 1칸
    assert 0 < refresher.timeouts[0] <= 2.0  # 설정값과 남은 기한 중 짧은 쪽
    assert body["asOf"] == "2026-11-17T19:41:00+09:00"  # 방금 받은 시각
    assert "(19시 41분 기준)" in body["answer"]


def test_question_without_seats_does_not_refresh():
    body, refresher = _ask_with(
        ScriptedLlm(tool("run_sql", sql=AFTER_20, explanation="x")), "오늘 20시 이후"
    )
    assert body["path"] == "NL2SQL" and refresher.calls == []


def test_seat_sql_without_any_date_asks_for_the_date():
    """Claude가 날짜 없이 잔여석 SQL을 써도 실행하지 않는다 — 짐작하지 않고 묻는다."""
    sql = SEATS_JOINS + "ORDER BY s.remaining_seats DESC LIMIT 3"
    body, refresher = _ask_with(
        ScriptedLlm(tool("run_sql", sql=sql, explanation="x")), "잔여석 많은 회차"
    )
    assert body["path"] == "CLARIFY" and body["rows"] is None and refresher.calls == []
    assert body["answer"] == "언제 보실 건가요? 날짜를 골라 주세요."
    choices = body["choices"]
    assert len(choices) == 7
    assert choices[0] == {"label": "오늘 11/17(화)", "message": "오늘 잔여석 많은 회차"}
    assert choices[1]["message"] == "내일 잔여석 많은 회차"
    assert choices[3] == {"label": "11/20(금)", "message": "11월 20일 잔여석 많은 회차"}


def test_claude_can_ask_for_the_date():
    body, _ = _ask_with(ScriptedLlm(tool("ask_user", missing="date")), "자리 남은 회차 있어?")
    assert (
        body["path"] == "CLARIFY" and body["choices"][0]["message"] == "오늘 자리 남은 회차 있어?"
    )


def test_wrong_date_is_sent_back_to_claude():
    """의미 검사: 질문은 내일인데 SQL은 오늘 → 실행하지 않고 다시 쓰게 한다."""
    wrong = (
        "SELECT m.title AS movie_title FROM screening s JOIN movie m ON m.movie_id = s.movie_id "
        "WHERE s.business_date = '2026-11-17'"
    )
    right = wrong.replace("2026-11-17", "2026-11-18")
    llm = ScriptedLlm(
        tool("run_sql", sql=wrong, explanation="x"), tool("run_sql", sql=right, explanation="x")
    )
    body, _ = _ask_with(llm, "내일 상영 영화")
    assert body["path"] == "NL2SQL" and body["llm"]["calls"] == 2
    feedback = llm.requests[1]["messages"][-1]["content"][0]["content"]
    assert "질문의 날짜는 2026-11-18" in feedback


def test_missing_theater_is_sent_back_to_claude():
    no_theater = (
        SEATS_JOINS.replace(", s.remaining_seats", "") + "WHERE s.business_date = '2026-11-17'"
    )
    with_theater = no_theater + " AND t.name = '건대입구'"
    llm = ScriptedLlm(
        tool("run_sql", sql=no_theater, explanation="x"),
        tool("run_sql", sql=with_theater, explanation="x"),
    )
    body, _ = _ask_with(llm, "오늘 건대 회차")
    assert body["path"] == "NL2SQL" and body["llm"]["calls"] == 2


def test_condition_still_wrong_after_retries_asks_the_user_to_confirm():
    wrong = (
        "SELECT m.title AS movie_title FROM screening s JOIN movie m ON m.movie_id = s.movie_id "
        "WHERE s.business_date = '2026-11-17'"
    )
    llm = ScriptedLlm(*[tool("run_sql", sql=wrong, explanation="x") for _ in range(3)])
    body, _ = _ask_with(llm, "내일 상영 영화")
    assert body["path"] == "CLARIFY" and body["llm"]["calls"] == 3
    assert body["answer"].startswith("11/18(수) 조건을 정확히 반영한 조회를 만들지 못했어요")
    # 같은 질문을 다시 보내는 버튼은 주지 않는다 — 같은 이유로 또 걸린다(#140 리뷰)
    assert body["answer"].endswith("다시 물어봐 주세요.") and body["choices"] == []


def test_unreadable_condition_is_not_blocked():
    """OR 로 쓴 날짜는 읽지 못한다 → 판단 보류(통과). 확실히 어긋난 것만 막는다."""
    sql = (
        "SELECT m.title AS movie_title FROM screening s JOIN movie m ON m.movie_id = s.movie_id "
        "WHERE (s.business_date = '2026-11-18' OR s.business_date = '2026-11-21')"
    )
    body, _ = _ask_with(ScriptedLlm(tool("run_sql", sql=sql, explanation="x")), "내일 상영 영화")
    assert body["path"] == "NL2SQL" and body["llm"]["calls"] == 1


def test_seat_sql_with_a_date_range_refreshes_today_only():
    """날짜를 범위로 쓰면 읽지 못한다 → 7일 × 극장 전부가 아니라 오늘만 새로 받고 알린다."""
    sql = SEATS_JOINS + "WHERE s.business_date >= '2026-11-17' ORDER BY s.remaining_seats DESC"
    body, refresher = _ask_with(
        ScriptedLlm(tool("run_sql", sql=sql, explanation="x")), "이번 주 자리 많은 회차"
    )
    assert body["path"] == "NL2SQL"
    assert refresher.calls == [([date(2026, 11, 17)], None)]  # 오늘 × 극장 전부(4번)
    assert "오늘이 아닌 날짜의 잔여석은 최대 10분 전 값" in body["answer"]


def test_seat_sql_with_many_dates_refreshes_the_first_two():
    days = "', '".join(f"2026-11-{d}" for d in (17, 18, 19, 20))
    sql = SEATS_JOINS + f"WHERE s.business_date IN ('{days}') AND t.name = '건대입구'"
    body, refresher = _ask_with(
        ScriptedLlm(tool("run_sql", sql=sql, explanation="x")), "건대입구 자리 많은 회차"
    )
    assert refresher.calls == [([date(2026, 11, 17), date(2026, 11, 18)], [10])]
    assert "오늘이 아닌 날짜의 잔여석은 최대 10분 전 값" in body["answer"]


def test_date_buttons_stay_within_the_contract():
    """버튼은 최대 7개, message 는 300자 이하 — 넘으면 날짜 말만 보낸다."""
    sql = SEATS_JOINS + "ORDER BY s.remaining_seats DESC LIMIT 3"
    long_question = "잔여석 많은 회차 " + "아" * 289
    body, _ = _ask_with(
        ScriptedLlm(tool("run_sql", sql=sql, explanation="x")), long_question, snapshot_days=9
    )
    assert body["path"] == "CLARIFY" and len(body["choices"]) == 7
    assert [c["message"] for c in body["choices"][:2]] == ["오늘", "내일"]
    assert all(len(c["message"]) <= 300 for c in body["choices"])


def test_unknown_seats_are_marked_in_the_answer():
    """강남 22:30 회차는 잔여석 확인 불가(NULL) — 숫자 대신 안내."""
    sql = SEATS_JOINS + "WHERE s.business_date = '2026-11-17' AND t.name = '강남'"
    body, _ = _ask_with(ScriptedLlm(tool("run_sql", sql=sql, explanation="x")), "오늘 강남 잔여석")
    assert [None] == [row[2] for row in body["rows"] if row[2] is None]
    assert "일부 회차는 잔여석을 확인하지 못했습니다(확인 불가)" in body["answer"]


def test_failed_refresh_says_the_seats_are_old():
    sql = SEATS_JOINS + "WHERE s.business_date = '2026-11-17' AND t.name = '건대입구'"
    body, _ = _ask_with(
        ScriptedLlm(tool("run_sql", sql=sql, explanation="x")),
        "오늘 건대입구 잔여석",
        refresher=FakeRefresher(Fresh(ok=False)),
    )
    assert "잔여석을 지금 새로 확인하지 못해 마지막으로 받은 값입니다" in body["answer"]
    assert body["asOf"] != "2026-11-17T19:41:00+09:00"  # 주기 갱신 시각 그대로
