"""질문 처리 순서: 가짜 Claude가 고른 도구에 따라 경로·재요청·거부가 맞게 나오는지."""

import asyncio
from datetime import datetime

import pytest

from seatsac_ai.common.errors import ApiError
from seatsac_ai.config import Settings
from seatsac_ai.llm.client import LlmUnavailable
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.snapshot.mapper import KST
from seatsac_ai.snapshot.store import SnapshotNotReady, SnapshotStore

from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store

NOW = datetime(2026, 11, 17, 19, 40, tzinfo=KST)
CONTRACT_KEYS = {
    "answer", "path", "columns", "rows", "rowCount", "truncated", "sql",
    "presetId", "asOf", "llm", "latencyMs", "rejection",
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
    assert body["answer"].startswith("오늘 ○시 이후 회차: 4건입니다.")


def test_nl2sql_path_returns_the_sql_that_ran():
    body = ask(ScriptedLlm(tool("run_sql", sql=AFTER_20, explanation="오늘 20시 이후 회차")))
    assert body["path"] == "NL2SQL" and body["rowCount"] == 4
    assert body["sql"].endswith("LIMIT 500")  # 검증기가 붙인 LIMIT 까지 보여 준다
    assert body["answer"].startswith("오늘 20시 이후 회차: 4건입니다.")


def test_out_of_scope():
    body = ask(ScriptedLlm(tool("out_of_scope", reason="날씨")), "내일 날씨 어때?")
    assert body["path"] == "OUT_OF_SCOPE" and body["rows"] is None and body["llm"]["calls"] == 1


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
    assert [t["name"] for t in req["tools"]] == ["run_preset", "run_sql", "out_of_scope"]


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
    assert [row[1] for row in body["rows"]] == [1, 2, 2, 3]


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
