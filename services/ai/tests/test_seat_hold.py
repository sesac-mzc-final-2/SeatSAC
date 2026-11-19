"""채팅으로 좌석 잡기(3.4.19, 플래그 APP_AI_SEAT_HOLD_ENABLED — 기본 켜짐, false 로 끈다).

가짜 Claude · fakeredis · 가짜 공개 API(httpx MockTransport)만 쓴다(실제 서버 호출 없음).
핵심: 확인 버튼 없이는 POST /seats/hold 가 한 번도 나가지 않고, 확인 뒤에는 정확히 한 번 나간다.
확인은 그 확인 요청의 ID가 실린 버튼 message 로만 — 지난 확인의 버튼이 새 확인을 확정하지 못한다.
"""

import asyncio
import json
import logging
import time
from datetime import datetime, timedelta

import fakeredis
import httpx
import pytest
from redis.exceptions import ConnectionError as RedisConnectionError

from seatsac_ai import seat_hold as holds
from seatsac_ai.cache.store import ChatCache, MemoryIndex, pending_key
from seatsac_ai.common.errors import ApiError
from seatsac_ai.config import Settings
from seatsac_ai.llm import prompt
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.llm.tools import tool_definitions
from seatsac_ai.snapshot.mapper import KST

from . import sql_fixtures
from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store

NOW = datetime(2026, 11, 17, 19, 40, tzinfo=KST)
USER = "user-1"
TOKEN = "Bearer user-access-token"
CONTRACT_KEYS = {
    "answer", "path", "columns", "rows", "rowCount", "truncated", "sql",
    "presetId", "asOf", "llm", "latencyMs", "rejection", "choices", "seatHold",
}  # fmt: skip
# sql_fixtures 회차 2: 별의 항해 · 건대입구 1관(auditorium 101) · 2026-11-17 20:00
SEATS = {"J7": 1007, "J8": 1008, "J9": 1009, "A1": 1001, "A2": 1002}
HELD = {
    "bookingId": 9876,
    "screeningId": 2,
    "status": "PENDING",
    "seatIds": [1007, 1008],
    "seatLabels": ["J7", "J8"],
    "seatCount": 2,
    "amount": 30000,
    "expiresAt": "2026-11-17T19:45:05+09:00",
    "createdAt": "2026-11-17T19:40:05+09:00",
}


class Api:
    """가짜 공개 API. seen = 받은 요청 전부, hold = POST /seats/hold 응답(상태, 본문) 또는 예외."""

    def __init__(
        self, hold=(201, HELD), taken=(), seats_status=200, version=3, layout_version=3, extra=()
    ):
        self.seen: list[httpx.Request] = []
        # hold 가 목록이면 시도마다 차례로(재시도 확인용), 아니면 늘 같은 답
        self.holds = list(hold) if isinstance(hold, list) else None
        self.hold = hold
        self.taken = set(taken)
        self.seats_status = seats_status
        self.version = version  # 좌석 현황이 말하는 배치 버전(None 이면 칸이 없다)
        self.layout_version = layout_version
        self.extra = list(extra)  # 배치도에 더 넣을 좌석

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        path = request.url.path
        if request.method == "POST" and path == "/api/seats/hold":
            reply = self.holds.pop(0) if self.holds else self.hold
            if isinstance(reply, Exception):
                raise reply
            return httpx.Response(reply[0], json=reply[1])
        if path.endswith("/seats"):
            if self.seats_status != 200:
                return httpx.Response(self.seats_status, json={"code": "SERVICE_UNAVAILABLE"})
            seats = [
                {"seatId": i, "status": "HELD" if label in self.taken else "AVAILABLE"}
                for label, i in SEATS.items()
            ]
            body = {"screeningId": 2, "seats": seats}
            if self.version is not None:
                body["layoutVersion"] = self.version
            return httpx.Response(200, json=body)
        if path.startswith("/api/auditoriums/"):
            seats = [
                {"seatId": i, "seatLabel": label, "active": True} for label, i in SEATS.items()
            ]
            seats.append({"seatId": 1999, "seatLabel": "Z9", "active": False})
            seats += self.extra
            return httpx.Response(200, json={"layoutVersion": self.layout_version, "seats": seats})
        return httpx.Response(404, json={"code": "NOT_FOUND"})

    @property
    def posts(self) -> list[httpx.Request]:
        return [r for r in self.seen if r.method == "POST"]


def make(llm, api=None, valkey=None, flag=True, store=None, **settings):
    api = api or Api()
    valkey = valkey or fakeredis.FakeAsyncRedis(decode_responses=True)
    cache = ChatCache(
        valkey, ttl=3600, session_ttl=1800, threshold=0.92, embedder=None, index=MemoryIndex()
    )
    client = holds.SeatHoldClient(
        "https://example.test/api", httpx.AsyncClient(transport=httpx.MockTransport(api))
    )
    service = ChatService(
        store or loaded_store(),
        llm,
        Settings(
            _env_file=None,
            APP_AI_SEAT_HOLD_ENABLED=flag,
            APP_AI_SEMANTIC_CACHE_ENABLED=False,
            **settings,
        ),
        clock=lambda: NOW,
        cache=cache,
        seat_hold=client,
    )
    return service, api, valkey


def ask(service, question, session_id=None, user=USER, now=NOW, authorization=TOKEN):
    return asyncio.run(
        service.answer(
            question, now=now, user_id=user, session_id=session_id, authorization=authorization
        )
    )


def hold_tool(seats=("J7", "J8"), **over):
    args = {"movie": "별의 항해", "theater": "건대입구", "date": "2026-11-17", "time": "20:00"}
    return tool("hold_seats", **(args | over), seats=list(seats))


QUESTION = "오늘 20시 별의 항해 건대입구 J7 J8 잡아줘"


def confirmed(service, question=QUESTION, **kwargs):
    """확인 요청까지 간 뒤 그 응답."""
    first = ask(service, question, **kwargs)
    assert first["path"] == "HOLD_CONFIRM", first["answer"]
    return first


def yes(body):
    """그 확인 요청의 「잡기」 버튼이 보내는 message (확인 ID가 실려 있다)."""
    return body["choices"][0]["message"]


def no(body):
    return body["choices"][1]["message"]


def press(service, body, **kwargs):
    return ask(service, yes(body), session_id=body["sessionId"], **kwargs)


# --- 플래그 off(false): 좌석 잡기가 없던 main(#146까지)과 같다 ---------------------------

# main(#146 3.4.17 머지 시점)의 역할 안내 — 플래그를 끄면(false) 글자 하나 다르지 않아야 한다
ROLE_MAIN = """너는 영화 예매 사이트 SeatSAC의 상영 정보 도우미다.
사용자의 질문을 보고 도구 하나를 반드시 호출한다. 글로 답하지 않는다.
1. 질문이 프리셋 하나와 뜻이 같으면 run_preset (SQL을 쓰지 않는다)
2. 프리셋은 없지만 아래 표로 답할 수 있으면 run_sql
3. 영화·극장·회차와 상관없거나 표에 없는 정보(가격·리뷰·예매 대행 등)면 out_of_scope
   (category로 종류를 고른다 — 인사·잡담·욕설처럼 원하는 것이 없는 말은 chitchat.
    reason에는 질문 원문·개인정보 없이 무엇을 원했는지만 짧게 적는다)
"내 예매" 같은 개인 정보는 표에 없다(서버가 따로 처리한다)."""
TOOLS_MAIN = ["run_preset", "run_sql", "ask_user", "out_of_scope"]
# main 의 out_of_scope category 설명 첫 문장(3.4.17) — 끄면 이 글자 그대로, 켜면 좌석 잡기를 뺀다
ACTION_REQUEST_MAIN = (
    "action_request=예매·좌석 잡기·취소·결제처럼 사이트가 하는 행동을 대신 해 달라는 요청. "
    "site_feature="
)


def _category_text(tools):
    out = next(t for t in tools if t["name"] == "out_of_scope")
    return out["input_schema"]["properties"]["category"]["description"]


def test_flag_off_keeps_tools_prompt_and_response_as_before():
    llm = ScriptedLlm(tool("out_of_scope", reason="x"), tool("out_of_scope", reason="x"))
    service, api, valkey = make(llm, flag=False)
    body = ask(service, QUESTION)
    assert set(body) == CONTRACT_KEYS | {"sessionId"} and body["seatHold"] is None
    sent = llm.requests[0]
    assert [t["name"] for t in sent["tools"]] == TOOLS_MAIN
    assert sent["system"][0]["text"].startswith(ROLE_MAIN + "\n\n## 프리셋\n")
    # Claude에 실제로 간 system 전문·도구 정의 전체가 좌석 잡기 없는 것과 같다
    assert sent["system"][0]["text"] == prompt.system_text(False)
    assert sent["tools"] == tool_definitions()
    assert "hold_seats" not in json.dumps(sent, ensure_ascii=False, default=str)
    # 확인 문장을 보내도 평소 질문처럼 Claude로 간다 — 대기 행동을 보지도 않는다
    key = pending_key(USER, body["sessionId"])
    asyncio.run(valkey.set(key, json.dumps({"kind": "CONFIRM", "confirmId": "abc123"})))
    again = ask(service, "좌석 잡기 #abc123", session_id=body["sessionId"])
    assert again["path"] == "OUT_OF_SCOPE" and len(llm.requests) == 2
    assert api.seen == [] and asyncio.run(valkey.get(key)) is not None


def test_flag_off_ignores_a_hold_tool_call():
    """꺼져 있는데 Claude가 hold_seats 를 불러도(있을 수 없지만) 모르는 도구로 다시 묻는다."""
    llm = ScriptedLlm(hold_tool(), tool("out_of_scope", reason="x"))
    service, api, _ = make(llm, flag=False)
    assert ask(service, QUESTION)["path"] == "OUT_OF_SCOPE"
    assert api.seen == []


def test_flag_off_prompt_and_tools_are_the_main_ones():
    assert prompt._ROLE == ROLE_MAIN
    assert _category_text(tool_definitions()).startswith(ACTION_REQUEST_MAIN)
    assert [t["name"] for t in tool_definitions()] == TOOLS_MAIN
    assert "hold_seats" not in prompt.system_text() and "hold_seats" not in json.dumps(
        tool_definitions(), ensure_ascii=False
    )


def test_flag_on_adds_the_hold_tool_and_a_role_without_the_contradiction():
    assert [t["name"] for t in tool_definitions(seat_hold=True)] == [*TOOLS_MAIN, "hold_seats"]
    on_tools, off_tools = tool_definitions(seat_hold=True), tool_definitions()
    # 앞의 세 도구는 같고, out_of_scope 는 action_request 설명만 다르다
    assert on_tools[:3] == off_tools[:3]
    on_text, off_text = _category_text(on_tools), _category_text(off_tools)
    assert on_text != off_text
    assert on_text.split("site_feature=")[1] == off_text.split("site_feature=")[1]
    on_action = on_text.split("site_feature=")[0]
    # "좌석 잡기 → 범위 밖(action_request)"이 hold_seats 와 부딪치지 않는다
    assert "좌석 잡기" not in on_action and "hold_seats" in on_action
    on = prompt.system_text(True)
    assert (
        "hold_seats" in on and "예매 대행" not in on
    )  # 3번(예매 대행 → 범위 밖)과 부딪치지 않는다
    assert "category로 종류를 고른다" in on  # 3.4.17의 분류 안내는 켜져 있어도 남는다
    # 역할 안내 뒤(프리셋·표 설명)는 같다
    assert on.split("\n\n## 프리셋\n")[1] == prompt.system_text().split("\n\n## 프리셋\n")[1]


def test_default_settings_turn_seat_hold_on(monkeypatch):
    """값을 주지 않으면 켜짐 — 도구 5개·좌석 잡기 역할 안내로 Claude를 부르고 확인 요청까지 간다."""
    monkeypatch.delenv("APP_AI_SEAT_HOLD_ENABLED", raising=False)
    assert Settings(_env_file=None).seat_hold_enabled is True
    assert Settings(_env_file=None, APP_AI_SEAT_HOLD_ENABLED="false").seat_hold_enabled is False
    llm = ScriptedLlm(hold_tool())
    api = Api()
    cache = ChatCache(
        fakeredis.FakeAsyncRedis(decode_responses=True),
        ttl=3600, session_ttl=1800, threshold=0.92, embedder=None, index=MemoryIndex(),
    )  # fmt: skip
    client = holds.SeatHoldClient(
        "https://example.test/api", httpx.AsyncClient(transport=httpx.MockTransport(api))
    )
    service = ChatService(
        loaded_store(),
        llm,
        Settings(_env_file=None, APP_AI_SEMANTIC_CACHE_ENABLED=False),  # 플래그 값 없음
        clock=lambda: NOW,
        cache=cache,
        seat_hold=client,
    )
    body = ask(service, QUESTION)
    assert body["path"] == "HOLD_CONFIRM" and api.posts == []  # 확인 전에는 선점하지 않는다
    sent = llm.requests[0]
    assert [t["name"] for t in sent["tools"]] == [*TOOLS_MAIN, "hold_seats"]
    assert sent["system"][0]["text"] == prompt.system_text(True)


def test_default_on_without_valkey_or_client_stays_off(monkeypatch):
    """켜져 있어도(기본) ai Valkey·공개 API 클라이언트가 없으면 꺼진 것으로 동작한다."""
    monkeypatch.delenv("APP_AI_SEAT_HOLD_ENABLED", raising=False)
    service = ChatService(
        loaded_store(),
        ScriptedLlm(tool("out_of_scope", reason="x")),
        Settings(_env_file=None),
        clock=lambda: NOW,
    )
    body = asyncio.run(service.answer(QUESTION, now=NOW, user_id=USER))
    assert body["seatHold"] is None and [t["name"] for t in service._tools] == TOOLS_MAIN


# --- 확인 요청 → 확인 → 선점 1회 -------------------------------------------------


def test_confirm_request_does_not_hold_and_stores_a_pending_action():
    llm = ScriptedLlm(hold_tool())
    service, api, valkey = make(llm)
    body = confirmed(service)
    assert set(body) == CONTRACT_KEYS | {"sessionId"}
    # 금액은 적지 않는다(요금 규칙은 booking 의 것)
    assert body["answer"] == (
        "별의 항해 11/17(화) 20:00 건대입구 1관 J7·J8 — 2석. "
        "이 좌석을 5분간 잡을까요? (2분 안에 눌러 주세요)"
    )
    key = pending_key(USER, body["sessionId"])
    pending = json.loads(asyncio.run(valkey.get(key)))
    confirm_id = pending["confirmId"]
    assert len(confirm_id) == 6
    # 버튼 글자에 좌석이 있고, message 에 이 확인 요청의 ID가 실린다
    assert body["choices"] == [
        {"label": "J7·J8 잡기", "message": f"좌석 잡기 #{confirm_id}"},
        {"label": "취소", "message": f"취소 #{confirm_id}"},
    ]
    assert body["seatHold"] == {
        "screeningId": 2, "bookingId": None, "seatLabels": ["J7", "J8"], "amount": None,
        "expiresAt": None, "confirmExpiresAt": "2026-11-17T19:42:00+09:00", "reason": None,
    }  # fmt: skip
    assert body["llm"]["calls"] == 1 and body["rows"] is None
    assert api.posts == []  # 아직 선점하지 않았다
    # 좌석 현황 → 배치도(회차의 배치 버전) 순서로 공개 API만 불렀다. 토큰 없이
    assert [r.url.path for r in api.seen] == [
        "/api/screenings/2/seats",
        "/api/auditoriums/101/layout",
    ]
    assert api.seen[1].url.params["version"] == "3"
    assert all("authorization" not in r.headers for r in api.seen)
    assert pending["userId"] == USER and pending["seatIds"] == [1007, 1008]
    assert 0 < asyncio.run(valkey.ttl(key)) <= 120
    assert "Bearer" not in json.dumps(pending)  # 토큰은 저장하지 않는다


def test_confirm_button_holds_exactly_once_with_the_stored_key_and_users_token():
    llm = ScriptedLlm(hold_tool())
    service, api, valkey = make(llm)
    first = confirmed(service)
    stored = json.loads(asyncio.run(valkey.get(pending_key(USER, first["sessionId"]))))
    body = press(service, first)
    assert body["path"] == "HOLD_RESULT" and body["llm"]["calls"] == 0 and len(llm.requests) == 1
    assert body["answer"] == "J7·J8 좌석을 잡았습니다. 19:45까지 결제를 마쳐 주세요."
    assert body["seatHold"] == {
        "screeningId": 2, "bookingId": 9876, "seatLabels": ["J7", "J8"], "amount": 30000,
        "expiresAt": "2026-11-17T19:45:05+09:00", "confirmExpiresAt": None, "reason": None,
    }  # fmt: skip
    assert body["choices"] is None
    assert body["sessionId"] == first["sessionId"]  # 흐름 동안 sessionId 가 바뀌지 않는다
    (post,) = api.posts
    assert post.url.path == "/api/seats/hold"
    assert json.loads(post.content) == {"screeningId": 2, "seatIds": [1007, 1008]}
    assert post.headers["authorization"] == TOKEN
    assert post.headers["idempotency-key"] == stored["idempotencyKey"]
    # 대기 행동은 한 번 쓰고 지운다 — 버튼을 또 눌러도 다시 잡지 않는다
    again = press(service, first)
    assert again["path"] == "HOLD_GUIDE" and again["seatHold"]["reason"] == "NOT_CONFIRMED"
    assert len(api.posts) == 1 and len(llm.requests) == 1


# --- 확인한 것만 잡는다 (확인 ID) --------------------------------------------------


def test_an_old_confirm_button_cannot_confirm_a_newer_request():
    """A 확인 요청 → B 확인 요청 뒤, A의 버튼(또는 그 재전송)은 B를 확정하지 못한다."""
    llm = ScriptedLlm(hold_tool(["J7", "J8"]), hold_tool(["A1"]))
    service, api, valkey = make(llm)
    a = confirmed(service)
    b = confirmed(service, "오늘 20시 별의 항해 건대입구 A1 잡아줘", session_id=a["sessionId"])
    assert b["sessionId"] == a["sessionId"] and yes(a) != yes(b)
    stale = press(service, a)
    assert stale["path"] == "HOLD_GUIDE" and stale["seatHold"]["reason"] == "NOT_CONFIRMED"
    assert "지난 확인 요청" in stale["answer"] and api.posts == []
    # 어긋난 확인이 오면 B도 내려놓는다(안전 우선) — B의 버튼도 더는 잡지 않는다
    assert press(service, b)["seatHold"]["reason"] == "NOT_CONFIRMED" and api.posts == []


def test_the_newer_request_is_the_one_that_gets_held():
    llm = ScriptedLlm(hold_tool(["J7", "J8"]), hold_tool(["A1"]))
    service, api, _ = make(llm, Api(hold=(201, HELD | {"seatLabels": ["A1"], "amount": 15000})))
    a = confirmed(service)
    b = confirmed(service, "오늘 20시 별의 항해 건대입구 A1 잡아줘", session_id=a["sessionId"])
    done = press(service, b)
    assert done["path"] == "HOLD_RESULT" and done["seatHold"]["reason"] is None
    assert json.loads(api.posts[0].content)["seatIds"] == [1001]


@pytest.mark.parametrize("typed", ["좌석 잡기", " 좌석 잡기 ", "좌석 잡기 #zzzzzz", "네", "확인"])
def test_typing_instead_of_pressing_the_button_never_holds(typed):
    """ID 없는 「좌석 잡기」는 확인이 아니다(버튼을 누르라고 안내). 대기 행동은 그대로 둔다."""
    llm = ScriptedLlm(hold_tool(), tool("out_of_scope", reason="x"))
    service, api, valkey = make(llm)
    first = confirmed(service)
    body = ask(service, typed, session_id=first["sessionId"])
    assert api.posts == []
    if typed.strip() == "좌석 잡기":
        assert body["path"] == "HOLD_GUIDE" and "버튼을 눌러" in body["answer"]
        assert len(llm.requests) == 1
        # 버튼은 여전히 쓸 수 있다
        assert press(service, first)["seatHold"]["bookingId"] == 9876 and len(api.posts) == 1
    else:
        assert body["path"] == "OUT_OF_SCOPE"  # 다른 질문 — 대기 행동은 사라졌다
        assert press(service, first)["seatHold"]["reason"] == "NOT_CONFIRMED" and api.posts == []


def test_confirm_without_any_pending_action_does_nothing():
    llm = ScriptedLlm()
    service, api, _ = make(llm)
    for message in ("좌석 잡기", "좌석 잡기 #abc123", "취소", "취소 #abc123"):
        body = ask(service, message)
        assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == "NOT_CONFIRMED"
    assert api.seen == [] and llm.requests == []


@pytest.mark.parametrize("case", ["other_session", "other_user", "no_session"])
def test_pending_action_belongs_to_one_user_and_one_session(case):
    llm = ScriptedLlm(hold_tool())
    service, api, _ = make(llm)
    first = confirmed(service)
    if case == "other_session":
        body = ask(service, yes(first), session_id="0b9f1c3e-6d0a-4b7e-9a51-1f2e3d4c5b6a")
    elif case == "other_user":
        body = ask(service, yes(first), session_id=first["sessionId"], user="user-2")
    else:
        body = ask(service, yes(first))
    assert body["seatHold"]["reason"] == "NOT_CONFIRMED" and api.posts == []


def test_pending_action_of_another_user_under_my_key_is_refused():
    """키가 사용자별이라 올 수 없지만, 값에 묶인 사용자 ID도 본다."""
    service, api, valkey = make(ScriptedLlm(hold_tool()))
    first = confirmed(service)
    stolen = asyncio.run(valkey.get(pending_key(USER, first["sessionId"])))
    asyncio.run(valkey.set(pending_key("user-2", first["sessionId"]), stolen))
    body = ask(service, yes(first), session_id=first["sessionId"], user="user-2")
    assert body["seatHold"]["reason"] == "NOT_CONFIRMED" and api.posts == []


def test_expired_pending_action_is_not_used():
    service, api, _ = make(ScriptedLlm(hold_tool()))
    first = confirmed(service)
    body = press(service, first, now=NOW + timedelta(seconds=121))
    assert body["seatHold"]["reason"] == "NOT_CONFIRMED" and api.posts == []


def test_confirm_ttl_follows_the_setting():
    service, _, valkey = make(ScriptedLlm(hold_tool()), APP_AI_SEAT_HOLD_CONFIRM_TTL="30s")
    first = confirmed(service)
    assert 0 < asyncio.run(valkey.ttl(pending_key(USER, first["sessionId"]))) <= 30
    assert first["seatHold"]["confirmExpiresAt"] == "2026-11-17T19:40:30+09:00"


@pytest.mark.parametrize("typed", [False, True])
def test_cancel_drops_the_pending_action(typed):
    service, api, _ = make(ScriptedLlm(hold_tool()))
    first = confirmed(service)
    body = ask(service, "취소" if typed else no(first), session_id=first["sessionId"])
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == "CANCELED"
    assert body["answer"] == "좌석을 잡지 않았습니다." and body["llm"]["calls"] == 0
    assert press(service, first)["seatHold"]["reason"] == "NOT_CONFIRMED" and api.posts == []


def test_another_question_drops_the_pending_action():
    llm = ScriptedLlm(hold_tool(), tool("out_of_scope", reason="날씨"))
    service, api, _ = make(llm)
    first = confirmed(service)
    other = ask(service, "내일 날씨 어때?", session_id=first["sessionId"])
    assert other["path"] == "OUT_OF_SCOPE" and other["seatHold"] is None
    assert press(service, first)["seatHold"]["reason"] == "NOT_CONFIRMED" and api.posts == []


def test_two_confirms_at_once_hold_only_once():
    """같은 버튼이 동시에 두 번 와도(동시 1건 잠금이 없다고 쳐도) GETDEL 이 한 번만 내준다."""
    service, api, _ = make(ScriptedLlm(hold_tool()))
    first = confirmed(service)

    async def both():
        send = service.answer(
            yes(first), now=NOW, user_id=USER, session_id=first["sessionId"], authorization=TOKEN
        )
        again = service.answer(
            yes(first), now=NOW, user_id=USER, session_id=first["sessionId"], authorization=TOKEN
        )
        return await asyncio.gather(send, again)

    bodies = asyncio.run(both())
    assert sorted(b["path"] for b in bodies) == ["HOLD_GUIDE", "HOLD_RESULT"]
    assert len(api.posts) == 1


def test_valkey_failure_when_storing_means_no_confirm_request(monkeypatch):
    """대기 행동을 저장하지 못하면 확인 요청을 만들지 않는다."""
    service, api, valkey = make(ScriptedLlm(hold_tool()))
    real_set = valkey.set

    async def failing_set(key, *args, **kwargs):
        if key.startswith("ai:pending:"):
            raise RedisConnectionError("down")
        return await real_set(key, *args, **kwargs)

    monkeypatch.setattr(valkey, "set", failing_set)
    body = ask(service, QUESTION)
    assert body["path"] == "HOLD_GUIDE" and body["choices"] is None
    assert body["seatHold"]["reason"] == "UPSTREAM_UNAVAILABLE" and api.posts == []


def test_valkey_failure_when_taking_means_no_hold(monkeypatch):
    """대기 행동을 꺼내지 못하면(Valkey 오류) 확인 버튼이 와도 잡지 않는다."""
    service, api, valkey = make(ScriptedLlm(hold_tool()))
    first = confirmed(service)

    async def failing_getdel(key):
        raise RedisConnectionError("down")

    monkeypatch.setattr(valkey, "getdel", failing_getdel)
    body = press(service, first)
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == "NOT_CONFIRMED"
    assert api.posts == []


def test_missing_token_at_confirm_is_401_before_the_pending_action_is_taken():
    service, api, valkey = make(ScriptedLlm(hold_tool()))
    first = confirmed(service)
    with pytest.raises(ApiError) as error:
        press(service, first, authorization=None)
    assert error.value.status == 401 and api.posts == []
    # 대기 행동은 그대로다 — 다시 로그인한 뒤 같은 버튼으로 잡을 수 있다
    assert asyncio.run(valkey.get(pending_key(USER, first["sessionId"]))) is not None
    assert press(service, first)["seatHold"]["bookingId"] == 9876


# --- 회차 0 / 1 / 여럿 -----------------------------------------------------------


def test_no_matching_screening():
    service, api, _ = make(ScriptedLlm(hold_tool(time="23:55")))
    body = ask(service, QUESTION)
    assert body["path"] == "HOLD_GUIDE" and body["choices"] is None
    assert body["seatHold"]["reason"] == "SCREENING_NOT_FOUND" and api.seen == []


def test_unknown_movie_is_named_in_the_answer():
    service, _, _ = make(ScriptedLlm(hold_tool(movie="부활남")))
    body = ask(service, "오늘 20시 부활남 건대입구 J7 J8 잡아줘")
    assert body["seatHold"]["reason"] == "SCREENING_NOT_FOUND" and "'부활남' 영화" in body["answer"]


@pytest.mark.parametrize(
    ("args", "word"),
    [
        ({}, "영화나 극장·날짜나 시각"),  # 좌석만
        ({"date": "2026-11-17", "time": "20:00"}, "영화나 극장"),
        ({"movie": "별의 항해", "theater": "건대입구"}, "날짜나 시각"),
    ],
)
def test_too_few_conditions_ask_instead_of_guessing_a_screening(args, word):
    """(영화 또는 극장) + (날짜 또는 시각)이 없으면 회차를 짐작으로 좁히지 않는다."""
    service, api, valkey = make(ScriptedLlm(tool("hold_seats", **args, seats=["J7"])))
    body = ask(service, "J7 잡아줘")
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == "SCREENING_AMBIGUOUS"
    assert word in body["answer"] and body["choices"] is None
    assert api.seen == [] and asyncio.run(valkey.keys("ai:pending:*")) == []


@pytest.mark.parametrize(
    "args",
    [
        {"theater": "건대입구", "time": "20:00"},
        {"movie": "별의 항해", "date": "2026-11-17"},
    ],
)
def test_the_smallest_allowed_conditions_still_find_one_screening(args):
    service, _, _ = make(ScriptedLlm(tool("hold_seats", **args, seats=["J7"])))
    body = ask(service, "J7 잡아줘")
    assert body["path"] == "HOLD_CONFIRM" and body["seatHold"]["screeningId"] == 2


def test_started_or_closed_screening_is_refused():
    # 회차 1(18:00)은 이미 시작, 회차 4(19:50)는 19:46부터 예매 마감(시작 5분 전)
    service, api, _ = make(ScriptedLlm(hold_tool(time="18:00")))
    assert ask(service, QUESTION)["seatHold"]["reason"] == "SCREENING_STARTED"
    closing = hold_tool(["A1"], movie="고양이 탐정", theater="강남", time="19:50")
    service, api, _ = make(ScriptedLlm(closing, closing, closing))
    question = "오늘 19시 50분 고양이 탐정 강남 A1 잡아줘"
    assert ask(service, question, now=NOW + timedelta(minutes=4))["path"] == "HOLD_CONFIRM"
    for minutes in (5, 6):  # 19:45 정각부터 마감
        body = ask(service, question, now=NOW + timedelta(minutes=minutes))
        assert body["seatHold"]["reason"] == "SCREENING_STARTED"
    assert api.posts == []


def many_screenings(monkeypatch, open_count, past=60):
    """오늘 건대입구에 지나간 회차 past 개 + 예매할 수 있는 회차 open_count 개(22:01부터)."""

    def row(screening_id, starts_at, hour):
        day = sql_fixtures.TODAY
        return (screening_id, 1, 10, "1관", "NORMAL", day, starts_at, hour, 50, 100, "BOOKING")

    rows = [row(100 + i, f"2026-11-17 {8 + i // 10:02d}:{i % 10:02d}", 8) for i in range(past)]
    rows += [row(500 + i, f"2026-11-17 22:{i + 1:02d}", 22) for i in range(open_count)]
    monkeypatch.setattr(sql_fixtures, "SCREENINGS", rows)
    return loaded_store()


def test_many_past_screenings_do_not_hide_the_one_that_is_open(monkeypatch):
    """마감 조건이 SQL에 있다 — 앞선(지나간) 회차가 50개를 넘어도 예매할 수 있는 회차를 찾는다."""
    store = many_screenings(monkeypatch, open_count=1)
    llm = ScriptedLlm(tool("hold_seats", theater="건대입구", date="2026-11-17", seats=["J7"]))
    service, _, _ = make(llm, store=store)
    body = ask(service, "오늘 건대입구 J7 잡아줘")
    assert body["path"] == "HOLD_CONFIRM" and body["seatHold"]["screeningId"] == 500


@pytest.mark.parametrize(("open_count", "buttons"), [(2, 2), (7, 7), (8, None)])
def test_screening_buttons_up_to_seven_then_ask_for_more(monkeypatch, open_count, buttons):
    store = many_screenings(monkeypatch, open_count=open_count)
    llm = ScriptedLlm(tool("hold_seats", theater="건대입구", date="2026-11-17", seats=["J7"]))
    service, api, valkey = make(llm, store=store)
    body = ask(service, "오늘 건대입구 J7 잡아줘")
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == "SCREENING_AMBIGUOUS"
    assert (len(body["choices"]) if body["choices"] else None) == buttons
    assert api.seen == []
    assert len(asyncio.run(valkey.keys("ai:pending:*"))) == (1 if buttons else 0)


def test_several_screenings_ask_which_one_then_continue_without_claude():
    # 오늘 건대입구: 회차 2(별의 항해 20:00)·3(한강의 밤 21:00)이 예매 가능
    llm = ScriptedLlm(tool("hold_seats", theater="건대", date="2026-11-17", seats=["j7", "J 8"]))
    service, api, _ = make(llm)
    first = ask(service, "오늘 건대 J7 J8 잡아줘")
    assert first["path"] == "HOLD_GUIDE" and first["seatHold"]["reason"] == "SCREENING_AMBIGUOUS"
    assert [c["label"] for c in first["choices"]] == [
        "별의 항해 11/17(화) 20:00 건대입구 1관",
        "한강의 밤 11/17(화) 21:00 건대입구 2관",
    ]
    assert api.seen == []
    second = ask(service, first["choices"][0]["message"], session_id=first["sessionId"])
    assert second["path"] == "HOLD_CONFIRM" and second["seatHold"]["screeningId"] == 2
    assert second["seatHold"]["seatLabels"] == ["J7", "J8"] and len(llm.requests) == 1
    assert second["sessionId"] == first["sessionId"] and api.posts == []
    third = press(service, second)
    assert third["seatHold"]["bookingId"] == 9876 and len(api.posts) == 1
    assert third["sessionId"] == first["sessionId"]


def test_screening_picked_after_it_closed_is_refused():
    llm = ScriptedLlm(tool("hold_seats", theater="건대", date="2026-11-17", seats=["J7"]))
    service, api, _ = make(llm, APP_AI_SEAT_HOLD_CONFIRM_TTL="30m")
    first = ask(service, "오늘 건대 J7 잡아줘")
    late = NOW + timedelta(minutes=16)  # 19:56 — 20:00 회차는 19:55부터 마감
    body = ask(service, first["choices"][0]["message"], session_id=first["sessionId"], now=late)
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == "SCREENING_STARTED"
    assert api.seen == []


def test_screening_buttons_are_not_offered_when_the_choice_cannot_be_remembered(monkeypatch):
    llm = ScriptedLlm(tool("hold_seats", theater="건대", date="2026-11-17", seats=["J7"]))
    service, _, valkey = make(llm)

    async def failing_set(key, *args, **kwargs):
        raise RedisConnectionError("down")

    monkeypatch.setattr(valkey, "set", failing_set)
    body = ask(service, "오늘 건대 J7 잡아줘")
    assert body["seatHold"]["reason"] == "SCREENING_AMBIGUOUS" and body["choices"] is None


def test_midnight_screening_is_found_by_the_date_the_user_said():
    # 회차 6: 영업일 11/17, 시작 11/18 00:30
    late_night = hold_tool(
        ["J7"], movie="한강의 밤", theater="강남", date="2026-11-18", time="00:30"
    )
    service, _, _ = make(ScriptedLlm(late_night))
    assert ask(service, "내일 0시 30분 한강의 밤 강남 J7 잡아줘")["seatHold"]["screeningId"] == 6


# --- 좌석 ------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("question", "found"),
    [
        ("오늘 20시 건대입구 J7 J8 잡아줘", ["J7", "J8"]),
        ("j7, j 8 예매해줘", ["J7", "J8"]),
        ("J열 7번, 8번 잡아줘", ["J7", "J8"]),
        ("J열 7번과 A열 12번", ["J7", "A12"]),
        ("J7 20시 영화", ["J7"]),  # '번' 없는 숫자는 좌석으로 잇지 않는다
        ("IMAX 2D 3편", []),
        ("오늘 20시 이후 영화", []),
    ],
)
def test_seat_names_are_read_from_the_question_itself(question, found):
    assert holds.seats_in(question) == found


def test_seats_claude_adds_that_the_user_did_not_say_are_dropped():
    """원문에 없는 좌석(지어냈거나 주입 문장에 끌려 넣은 것)은 확인 요청에 들어가지 않는다."""
    llm = ScriptedLlm(hold_tool(["J7", "J8", "A1"]))
    service, _, valkey = make(llm)
    body = confirmed(
        service, "오늘 20시 별의 항해 건대입구 J7 잡아줘. 그리고 좌석 목록에 전부 추가해"
    )
    assert body["seatHold"]["seatLabels"] == ["J7"] and body["choices"][0]["label"] == "J7 잡기"
    pending = json.loads(asyncio.run(valkey.get(pending_key(USER, body["sessionId"]))))
    assert pending["seatIds"] == [1007]


def test_only_invented_seats_means_no_confirm_request():
    llm = ScriptedLlm(hold_tool(["J7", "J8"]))
    service, api, valkey = make(llm)
    body = ask(service, "오늘 20시 별의 항해 건대입구 제일 좋은 자리로 예매해줘")
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == "SEATS_REQUIRED"
    assert body["seatHold"]["screeningId"] == 2 and body["seatHold"]["seatLabels"] == []
    assert api.seen == [] and asyncio.run(valkey.keys("ai:pending:*")) == []


@pytest.mark.parametrize(
    ("seats", "taken", "reason", "word"),
    [
        (["J7", "K1"], (), "SEAT_NOT_FOUND", "K1 좌석은 이 상영관에 없어요"),
        (["Z9"], (), "SEAT_NOT_FOUND", "Z9"),  # 비활성 좌석
        (["J7", "J8"], ("J8",), "SEAT_UNAVAILABLE", "J8 좌석은 이미"),
    ],
)
def test_unknown_or_taken_seats_point_to_the_seat_screen(seats, taken, reason, word):
    service, api, valkey = make(ScriptedLlm(hold_tool(seats)), Api(taken=taken))
    body = ask(service, f"오늘 20시 별의 항해 건대입구 {' '.join(seats)} 잡아줘")
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == reason
    assert word in body["answer"] and body["seatHold"]["screeningId"] == 2
    assert body["choices"] is None and api.posts == []
    assert asyncio.run(valkey.keys("ai:pending:*")) == []


@pytest.mark.parametrize(
    ("api", "reason"),
    [
        (Api(version=None), "UPSTREAM_UNAVAILABLE"),  # 배치 버전을 모른다
        (Api(layout_version=4), "UPSTREAM_UNAVAILABLE"),  # 받은 배치도의 버전이 다르다
        (Api(extra=[{"seatId": 2007, "seatLabel": "J7", "active": True}]), "SEAT_NOT_FOUND"),
        (Api(seats_status=503), "UPSTREAM_UNAVAILABLE"),
    ],
)
def test_layout_that_cannot_be_trusted_means_no_confirm_request(api, reason):
    service, api, valkey = make(ScriptedLlm(hold_tool()), api)
    body = ask(service, QUESTION)
    assert body["path"] == "HOLD_GUIDE" and body["seatHold"]["reason"] == reason
    assert body["seatHold"]["screeningId"] == 2 and body["choices"] is None
    assert api.posts == [] and asyncio.run(valkey.keys("ai:pending:*")) == []


def test_layout_is_never_read_without_a_version():
    service, api, _ = make(ScriptedLlm(hold_tool()), Api(version=None))
    ask(service, QUESTION)
    assert [r.url.path for r in api.seen] == ["/api/screenings/2/seats"]


def test_five_seats_are_refused_before_any_call():
    seats = ["J7", "J8", "J9", "A1", "A2"]
    service, api, _ = make(ScriptedLlm(hold_tool(seats)))
    body = ask(service, f"오늘 20시 별의 항해 건대입구 {' '.join(seats)} 잡아줘")
    assert body["seatHold"]["reason"] == "TOO_MANY_SEATS" and api.seen == []


def test_no_seat_names_sends_the_user_to_the_seat_screen():
    service, api, _ = make(ScriptedLlm(hold_tool([])))
    body = ask(service, "오늘 20시 별의 항해 건대입구 예매해줘")
    assert body["seatHold"]["reason"] == "SEATS_REQUIRED" and body["seatHold"]["screeningId"] == 2
    assert api.seen == []  # 좌석을 대신 고르지 않는다


def test_bad_date_from_claude_is_sent_back_without_seat_data():
    llm = ScriptedLlm(hold_tool(date="11/17"), hold_tool())
    service, _, _ = make(llm)
    assert ask(service, QUESTION)["path"] == "HOLD_CONFIRM"
    feedback = llm.requests[1]["messages"][-1]["content"][0]
    assert feedback["is_error"] and "date 형식" in feedback["content"]


def test_external_calls_use_the_same_timeouts_as_my_bookings():
    timeout = holds._timeout(time.monotonic() + 25)
    assert (timeout.connect, timeout.read) == (1.0, 3.0)
    short = holds._timeout(time.monotonic() + 0.5)
    assert short.read <= 0.5 and short.connect <= 0.5
    assert holds._timeout(time.monotonic() - 1) is None


# --- 선점 결과 ---------------------------------------------------------------------


def error(code, details=()):
    return {"code": code, "message": "x", "traceId": "0" * 32, "details": list(details)}


@pytest.mark.parametrize(
    ("hold", "reason", "seat_hold", "posts"),
    [
        ((409, error("SEAT_UNAVAILABLE")), "SEAT_UNAVAILABLE", {"screeningId": 2}, 1),
        (
            (
                409,
                error(
                    "ACTIVE_HOLD_EXISTS",
                    [
                        {"field": "bookingId", "value": 9001},
                        {"field": "expiresAt", "value": "2026-11-17T19:44:00+09:00"},
                    ],
                ),
            ),
            "ACTIVE_HOLD_EXISTS",
            {"bookingId": 9001, "expiresAt": "2026-11-17T19:44:00+09:00"},
            1,
        ),
        ((400, error("SCREENING_STARTED")), "SCREENING_STARTED", {"bookingId": None}, 1),
        ((400, error("VALIDATION_FAILED")), "HOLD_FAILED", {"screeningId": 2}, 1),
        ((503, error("SERVICE_UNAVAILABLE")), "UPSTREAM_UNAVAILABLE", {"screeningId": 2}, 1),
        ((201, {"status": "PENDING"}), "UPSTREAM_UNAVAILABLE", {"bookingId": None}, 1),
        # 시간 초과: 같은 키로 한 번만 더 — 그래도 모르면 「보냈으나 결과 모름」
        (httpx.ReadTimeout("slow"), "UPSTREAM_UNAVAILABLE", {"screeningId": 2}, 2),
        # 재시도에서 「처리 중」이 오면 앞선 시도가 닿았다는 뜻 — 결과 모름
        (
            [httpx.ReadTimeout("slow"), (409, error("IDEMPOTENCY_IN_PROGRESS"))],
            "UPSTREAM_UNAVAILABLE",
            {"screeningId": 2},
            2,
        ),
    ],
)
def test_hold_failures_become_guidance_by_error_code(hold, reason, seat_hold, posts):
    llm = ScriptedLlm(hold_tool())
    service, api, _ = make(llm, Api(hold=hold))
    first = confirmed(service)
    body = press(service, first)
    assert body["path"] == "HOLD_RESULT" and body["seatHold"]["reason"] == reason
    assert body["seatHold"] | seat_hold == body["seatHold"] and body["choices"] is None
    assert len(api.posts) == posts and len(llm.requests) == 1
    assert len({p.headers["idempotency-key"] for p in api.posts}) == 1  # 늘 같은 키
    # 결과를 모르면 대기 행동은 쓰인 것으로 본다 — 버튼을 또 눌러도 다시 보내지 않는다
    assert press(service, first)["seatHold"]["reason"] == "NOT_CONFIRMED"
    assert len(api.posts) == posts


def test_timeout_then_success_on_the_retry_with_the_same_key():
    service, api, _ = make(
        ScriptedLlm(hold_tool()), Api(hold=[httpx.ReadTimeout("slow"), (201, HELD)])
    )
    first = confirmed(service)
    body = press(service, first)
    assert body["seatHold"]["bookingId"] == 9876 and body["seatHold"]["reason"] is None
    first_try, second_try = api.posts
    assert first_try.headers["idempotency-key"] == second_try.headers["idempotency-key"]
    assert first_try.content == second_try.content


def test_unknown_result_says_the_request_was_sent():
    service, _, _ = make(ScriptedLlm(hold_tool()), Api(hold=httpx.ReadTimeout("slow")))
    body = press(service, confirmed(service))
    assert "보냈지만 결과를 확인하지 못했어요" in body["answer"]


def test_request_that_never_left_keeps_the_pending_action_and_the_same_button():
    """연결을 맺지 못했으면 좌석은 잡히지 않았다 — 같은 버튼으로 다시 누를 수 있다."""
    api = Api(hold=[httpx.ConnectError("refused"), httpx.ConnectTimeout("slow"), (201, HELD)])
    service, api, valkey = make(ScriptedLlm(hold_tool()), api)
    first = confirmed(service)
    body = press(service, first)
    assert body["path"] == "HOLD_RESULT" and body["seatHold"]["reason"] == "HOLD_NOT_SENT"
    assert "보내지 못했어요(좌석은 잡히지 않았습니다)" in body["answer"]
    assert body["choices"] == first["choices"]  # 같은 확인 ID
    assert body["seatHold"]["confirmExpiresAt"] == first["seatHold"]["confirmExpiresAt"]
    assert len(api.posts) == 2  # 처음 + 재시도 1회
    assert 0 < asyncio.run(valkey.ttl(pending_key(USER, first["sessionId"]))) <= 120
    done = press(service, body)
    assert done["seatHold"]["bookingId"] == 9876 and len(api.posts) == 3
    assert len({p.headers["idempotency-key"] for p in api.posts}) == 1


def test_no_time_left_means_nothing_is_sent():
    service, api, valkey = make(ScriptedLlm(hold_tool()))
    first = confirmed(service)
    service._s.request_deadline_seconds = 0
    body = press(service, first)
    assert body["seatHold"]["reason"] == "HOLD_NOT_SENT" and api.posts == []
    assert body["choices"] == first["choices"]
    assert asyncio.run(valkey.get(pending_key(USER, first["sessionId"]))) is not None


def test_hold_401_is_passed_on_as_401():
    service, api, _ = make(ScriptedLlm(hold_tool()), Api(hold=(401, error("UNAUTHORIZED"))))
    first = confirmed(service)
    with pytest.raises(ApiError) as failure:
        press(service, first)
    assert failure.value.status == 401 and len(api.posts) == 1


# --- Claude·캐시·세션·로그에 남지 않는다 ----------------------------------------------


def test_booking_result_and_seat_status_never_reach_claude_or_the_cache(caplog):
    llm = ScriptedLlm(hold_tool(), hold_tool())
    service, api, valkey = make(llm)
    with caplog.at_level(logging.INFO):
        first = confirmed(service)
        done = press(service, first)
        again = ask(service, QUESTION, session_id=done["sessionId"])
    # Claude는 질문 문장만 받았다(확인·결과 단계에서는 부르지 않는다)
    assert len(llm.requests) == 2 and again["path"] == "HOLD_CONFIRM"  # 캐시로 답하지 않는다
    sent = json.dumps([r["messages"] for r in llm.requests], ensure_ascii=False, default=str)
    assert "9876" not in sent and "AVAILABLE" not in sent and "1007" not in sent
    # 조회 문맥(세션)·캐시에 남기지 않는다
    keys = asyncio.run(valkey.keys("*"))
    assert [k for k in keys if not k.startswith("ai:pending:")] == []
    # 로그: 결과 코드만 — 좌석 이름·예매 ID·토큰 없음
    fields = [getattr(r, "fields", {}) for r in caplog.records if r.message == "chat answered"]
    assert [f["path"] for f in fields] == ["HOLD_CONFIRM", "HOLD_RESULT", "HOLD_CONFIRM"]
    assert all(f["holdReason"] is None for f in fields)
    logged = " ".join(f"{r.getMessage()} {getattr(r, 'fields', '')}" for r in caplog.records)
    assert "9876" not in logged and "J7" not in logged and "user-access-token" not in logged


def test_looks_like_hold_needs_a_seat_name_and_a_verb():
    assert holds.looks_like_hold("오늘 20시 건대입구 J7 J8 잡아줘")
    assert holds.looks_like_hold("내일 a12 예매해줘")
    assert not holds.looks_like_hold("오늘 20시 이후 영화 알려줘")
    assert not holds.looks_like_hold("J7 좌석 어디야?")
    assert not holds.looks_like_hold("IMAX 2D 영화 예매해줘 방법")


def test_hold_metrics_exist_only_when_the_flag_is_on():
    from seatsac_ai.common import metrics

    assert metrics.CHAT_PATHS == (
        "cache_exact",
        "cache_semantic",
        "preset",
        "nl2sql",
        "my_bookings",
        "rejected",
        "out_of_scope",
    )  # fmt: skip  — #140 그대로
    assert metrics.chat_path_label("HOLD_CONFIRM") == "hold_confirm"
    created = []
    real = metrics.AI_CHAT.labels

    def labels(path, result):
        created.append(path)
        return real(path, result)

    metrics.AI_CHAT.labels = labels
    try:
        metrics.init_label_series()
        assert not any(p.startswith("hold_") for p in created)
        metrics.init_label_series(seat_hold=True)
        assert {p for p in created if p.startswith("hold_")} == set(metrics.HOLD_CHAT_PATHS)
    finally:
        del metrics.AI_CHAT.labels
    before = metrics.AI_CHAT.labels("hold_result", "ok")._value.get()
    service, _, _ = make(ScriptedLlm(hold_tool()))
    press(service, confirmed(service))
    assert metrics.AI_CHAT.labels("hold_result", "ok")._value.get() == before + 1
