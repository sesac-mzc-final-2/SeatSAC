"""6.4.3: 채팅의 답은 유지하고 정제된 수요만 DB 경계로 전달한다."""

import logging
import re
from datetime import datetime
from unittest.mock import AsyncMock

import pytest

from seatsac_ai.llm.client import NO_TOOL, ToolCall, Usage
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.snapshot.mapper import KST

from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store
from .test_cache_flow import ask, make

NOW = datetime(2026, 11, 17, 19, 40, tzinfo=KST)
QUESTION = "가상 극장에는 주차장 주변에 무슨 편의 시설이 있나요?"
AUTHORIZATION = "Bearer test-synthetic-token"


def test_joined_clarification_drops_copied_short_input_only_from_db(caplog):
    original = "잔여석 많은 회차 근처 주차장 요금"
    reason = "문의 요약: 홍. 길동"
    llm = ScriptedLlm(
        tool("ask_user", missing="date"),
        tool("out_of_scope", category="site_feature", reason=reason),
    )
    chat, _ = make(llm)
    demand = AsyncMock()
    chat._demand = demand
    first = ask(chat, original)
    assert first["path"] == "CLARIFY"
    caplog.clear()
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        second = ask(chat, "홍길동", session_id=first["sessionId"])
    assert second["path"] == "OUT_OF_SCOPE"
    assert f"질문: 홍길동 {original}" in llm.requests[1]["messages"][0]["content"]
    stored = demand.record.await_args.args[0]
    (logged,) = [r.fields for r in caplog.records if r.getMessage() == "unmet demand"]
    assert logged["reason"] == reason
    assert stored["reason"] is None and stored["reasonDropped"] is True
    assert stored["questionHash"] == logged["questionHash"]


def _chat(app, user_headers, client, replies, *, question=QUESTION, demand=None):
    app.state.chat = ChatService(
        loaded_store(), ScriptedLlm(*replies), app.state.settings, clock=lambda: NOW, demand=demand
    )
    return client.post(
        "/ai/chat",
        json={"message": question},
        headers={**user_headers, "Authorization": AUTHORIZATION},
    )


def test_chat_passes_only_anonymized_fields_to_demand(app, client, user_headers, caplog):
    demand = AsyncMock()
    unmasked = "주차장 안내 주소 mock@example.test 번호 010-1234-5678 " + "가" * 140
    reply = tool(
        "out_of_scope",
        category="  SITE_FEATURE\n",
        reason=unmasked,
        question=QUESTION,
        userId=user_headers["X-User-Id"],
        authorization=AUTHORIZATION,
        slots={"raw": QUESTION},
    )
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        response = _chat(app, user_headers, client, [reply], demand=demand)

    assert response.status_code == 200
    assert response.json()["path"] == "OUT_OF_SCOPE"
    demand.record.assert_awaited_once()
    (fields,) = demand.record.await_args.args
    assert set(fields) <= {"category", "reason", "questionHash", "questionLength", "reasonDropped"}
    assert fields["category"] == "site_feature"
    assert fields["reason"].startswith("주차장 안내 주소 [이메일] 번호 [숫자]")
    assert len(fields["reason"]) == 120
    assert re.fullmatch(r"[a-f0-9]{16}", fields["questionHash"])
    assert (
        0
        < demand.record.await_args.kwargs["timeout"]
        <= app.state.settings.request_deadline_seconds
    )
    (logged,) = [r.fields for r in caplog.records if r.getMessage() == "unmet demand"]
    assert logged == fields
    for excluded in (
        QUESTION,
        "mock@example.test",
        "010-1234-5678",
        *user_headers.values(),
        AUTHORIZATION,
    ):
        assert excluded not in repr(fields)


def test_copied_reason_is_dropped_but_demand_is_recorded(app, client, user_headers):
    demand = AsyncMock()
    reply = tool("out_of_scope", category="미지원 분류", reason=f"사용자 질문: {QUESTION}")
    response = _chat(app, user_headers, client, [reply], demand=demand)

    assert response.status_code == 200
    demand.record.assert_awaited_once()
    (fields,) = demand.record.await_args.args
    assert fields["category"] == "unknown"
    assert fields["reason"] is None and fields["reasonDropped"] is True
    assert QUESTION not in repr(fields)


@pytest.mark.parametrize(
    ("question", "reason"),
    [
        ("날씨", "날씨"),
        ("홍길동", "홍 길동!"),
        ("홍길동", "문의 요약: 홍. 길동"),
    ],
)
def test_short_question_copies_are_omitted_from_db_but_not_legacy_log(
    app, client, user_headers, caplog, question, reason
):
    demand = AsyncMock()
    reply = tool("out_of_scope", category="unrelated", reason=reason)
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        response = _chat(app, user_headers, client, [reply], question=question, demand=demand)

    assert response.status_code == 200 and response.json()["path"] == "OUT_OF_SCOPE"
    demand.record.assert_awaited_once()
    (stored,) = demand.record.await_args.args
    (logged,) = [r.fields for r in caplog.records if r.getMessage() == "unmet demand"]
    assert logged["reason"] == reason and "reasonDropped" not in logged
    assert stored["category"] == logged["category"] == "unrelated"
    assert stored["questionHash"] == logged["questionHash"]
    assert stored["reason"] is None and stored["reasonDropped"] is True


@pytest.mark.parametrize("path", ["chitchat", "no_tool", "rejected"])
def test_non_demand_paths_never_call_storage(app, client, user_headers, path):
    demand = AsyncMock()
    replies = {
        "chitchat": [tool("out_of_scope", category="  CHITCHAT  ", reason="인사")],
        "no_tool": [ToolCall("", NO_TOOL, {}, [], Usage(input=10, output=10))] * 2,
        "rejected": [tool("run_sql", sql="SELECT * FROM snapshot_meta", explanation="x")] * 3,
    }[path]
    response = _chat(app, user_headers, client, replies, demand=demand)

    assert response.status_code == 200
    assert response.json()["path"] == ("REJECTED" if path == "rejected" else "OUT_OF_SCOPE")
    demand.record.assert_not_awaited()


@pytest.mark.parametrize("question", ["😀", "!!!"])
def test_symbol_only_original_question_is_not_persisted(app, client, user_headers, question):
    demand = AsyncMock()
    response = _chat(
        app, user_headers, client,
        [tool("out_of_scope", category="unrelated", reason=f"요약: {question}")],
        question=question, demand=demand,
    )
    assert response.status_code == 200
    assert demand.record.await_args.args[0]["reason"] is None


def test_failed_storage_does_not_break_chat_or_expose_exception(app, client, user_headers, caplog):
    demand = AsyncMock()
    marker = "synthetic-db-error-details"
    demand.record.side_effect = RuntimeError(f"{marker}: {QUESTION}")
    reply = tool("out_of_scope", category="site_feature", reason="시설 안내 요청")
    response = _chat(app, user_headers, client, [reply], demand=demand)

    assert response.status_code == 200 and response.json()["path"] == "OUT_OF_SCOPE"
    demand.record.assert_awaited_once()
    assert any("RuntimeError" in r.getMessage() for r in caplog.records)
    for excluded in (marker, QUESTION, AUTHORIZATION):
        assert excluded not in caplog.text
        assert excluded not in response.text


def test_demand_off_preserves_chat_and_anonymized_log(app, client, user_headers, caplog):
    assert app.state.settings.unmet_demand_enabled is False
    reply = tool("out_of_scope", category="site_feature", reason="시설 안내 요청")
    with caplog.at_level(logging.INFO, logger="seatsac_ai.llm.orchestrator"):
        response = _chat(app, user_headers, client, [reply])

    assert response.status_code == 200 and response.json()["path"] == "OUT_OF_SCOPE"
    (fields,) = [r.fields for r in caplog.records if r.getMessage() == "unmet demand"]
    assert fields["category"] == "site_feature" and fields["reason"] == "시설 안내 요청"
