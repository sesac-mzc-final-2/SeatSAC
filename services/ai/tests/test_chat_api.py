"""POST /ai/chat 끝에서 끝까지(가짜 Claude · fakeredis · 값을 아는 스냅샷)."""

import re
from datetime import datetime

import fakeredis
from fastapi.testclient import TestClient

from seatsac_ai.config import Settings
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.main import create_app
from seatsac_ai.snapshot.mapper import KST

from .conftest import USER
from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store

UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")


def make_client(*replies, app_env="local", valkey="fake", **settings):
    s = Settings(_env_file=None, APP_ENV=app_env, **settings)
    store = loaded_store()
    llm = ScriptedLlm(*replies)
    fake = fakeredis.FakeAsyncRedis(decode_responses=True) if valkey == "fake" else None
    app = create_app(s, llm=llm, valkey=fake)
    now = datetime(2026, 11, 17, 19, 40, tzinfo=KST)  # sql_fixtures 기준 시각
    app.state.chat = ChatService(store, llm, s, clock=lambda: now)
    return TestClient(app, raise_server_exceptions=False)


def ask(client, message="오늘 20시 이후 영화"):
    return client.post("/ai/chat", json={"message": message}, headers=USER)


def test_preset_answer_has_every_contract_field():
    r = ask(make_client(tool("run_preset", presetId="today_after_time", params={"time": "20:00"})))
    assert r.status_code == 200
    body = r.json()
    assert UUID.match(body["sessionId"])
    assert body["path"] == "PRESET" and body["rowCount"] == 4
    assert body["asOf"].endswith("+09:00")
    assert body["columns"][0] == {"name": "movie_title", "type": "text"}


def test_eleventh_question_in_a_minute_is_429():
    replies = [tool("out_of_scope", reason="x") for _ in range(11)]
    client = make_client(*replies)
    codes = [ask(client).status_code for _ in range(11)]
    assert codes[:10] == [200] * 10 and codes[10] == 429


def test_staging_without_valkey_refuses_to_call_claude():
    client = make_client(tool("out_of_scope", reason="x"), app_env="staging", valkey=None)
    r = ask(client)
    assert r.status_code == 503 and r.json()["details"][0]["reason"] == "QUOTA_STORE_UNAVAILABLE"


def test_readiness_includes_valkey():
    r = make_client().get("/actuator/health/readiness")
    assert r.json()["components"]["valkey"] == "UP"


def test_snapshot_not_ready_does_not_use_up_the_quota():
    """한도는 Claude 비용을 지키는 장치라, Claude를 부르지 않는 503은 분당 10회에서 빼지 않는다."""
    client = make_client(tool("out_of_scope", reason="x"))
    chat = client.app.state.chat
    chat.ready = lambda: False  # 파드 재시작 직후처럼
    codes = [ask(client).status_code for _ in range(11)]
    assert codes == [503] * 11
    del chat.ready  # 적재가 끝나면
    assert ask(client).status_code == 200  # 429가 아니다


# --- 채팅으로 좌석 잡기(3.4.19, 플래그 기본 켜짐) — 배선과 HTTP 계층 ---------------------


def _public_api(seen):
    """가짜 공개 API(좌석 현황·배치도·선점). 실제 서버로 나가는 요청은 없다."""
    import httpx

    def handler(request):
        seen.append(request)
        if request.method == "POST":
            return httpx.Response(
                201,
                json={
                    "bookingId": 9876,
                    "seatLabels": ["J7"],
                    "amount": 15000,
                    "expiresAt": "2026-11-17T19:45:05+09:00",
                },
            )
        if request.url.path.endswith("/seats"):
            seats = [{"seatId": 1007, "status": "AVAILABLE"}]
            return httpx.Response(200, json={"layoutVersion": 1, "seats": seats})
        seats = [{"seatId": 1007, "seatLabel": "J7", "active": True}]
        return httpx.Response(200, json={"layoutVersion": 1, "seats": seats})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def test_create_app_wires_seat_hold_by_default_and_not_when_the_flag_is_off():
    from seatsac_ai.seat_hold import SeatHoldClient

    def app_with(**settings):
        values = {
            "PUBLIC_API_BASE_URL": "https://example.test/api",
            "APP_AI_SEMANTIC_CACHE_ENABLED": False,
        } | settings
        s = Settings(_env_file=None, **values)
        fake = fakeredis.FakeAsyncRedis(decode_responses=True)
        return create_app(
            s, llm=ScriptedLlm(), valkey=fake
        )  # 수명주기를 시작하지 않는다(호출 없음)

    base = ["run_preset", "run_sql", "ask_user", "out_of_scope"]
    off = app_with(APP_AI_SEAT_HOLD_ENABLED=False)
    assert off.state.seat_hold is None and off.state.chat._hold is None
    assert [t["name"] for t in off.state.chat._tools] == base
    on = app_with()  # 값을 주지 않으면 켜짐(기본)
    assert isinstance(on.state.seat_hold, SeatHoldClient) and on.state.chat._hold is not None
    assert [t["name"] for t in on.state.chat._tools] == [*base, "hold_seats"]
    # 켜져 있어도 공개 API 주소가 없으면 꺼진 것으로 동작한다
    no_api = app_with(PUBLIC_API_BASE_URL=None)
    assert no_api.state.seat_hold is None
    assert no_api.state.chat is None or no_api.state.chat._hold is None


def test_seat_hold_over_http_confirm_then_hold_with_the_request_token():
    from seatsac_ai.cache.store import ChatCache, MemoryIndex
    from seatsac_ai.seat_hold import SeatHoldClient

    s = Settings(_env_file=None, APP_AI_SEAT_HOLD_ENABLED=True, APP_AI_SEMANTIC_CACHE_ENABLED=False)
    hold = tool(
        "hold_seats", movie="별의 항해", theater="건대입구", date="2026-11-17", time="20:00",
        seats=["J7"],
    )  # fmt: skip
    llm = ScriptedLlm(hold)
    fake = fakeredis.FakeAsyncRedis(decode_responses=True)
    app = create_app(s, llm=llm, valkey=fake)
    seen = []
    now = datetime(2026, 11, 17, 19, 40, tzinfo=KST)
    cache = ChatCache(fake, ttl=3600, session_ttl=1800, threshold=0.92, index=MemoryIndex())
    app.state.chat = ChatService(
        loaded_store(), llm, s, clock=lambda: now, cache=cache,
        seat_hold=SeatHoldClient("https://example.test/api", _public_api(seen)),
    )  # fmt: skip
    client = TestClient(app, raise_server_exceptions=False)
    headers = USER | {"Authorization": "Bearer user-access-token"}

    first = client.post(
        "/ai/chat", json={"message": "오늘 20시 별의 항해 건대입구 J7 잡아줘"}, headers=headers
    ).json()
    assert first["path"] == "HOLD_CONFIRM" and first["choices"][0]["label"] == "J7 잡기"
    assert [r.method for r in seen] == ["GET", "GET"]

    button = {"message": first["choices"][0]["message"], "sessionId": first["sessionId"]}
    # 토큰 없이 누르면 401 — 선점하지 않는다
    assert client.post("/ai/chat", json=button, headers=USER).status_code == 401
    assert [r.method for r in seen] == ["GET", "GET"]

    done = client.post("/ai/chat", json=button, headers=headers).json()
    assert done["path"] == "HOLD_RESULT" and done["seatHold"]["bookingId"] == 9876
    assert done["sessionId"] == first["sessionId"] and done["llm"]["calls"] == 0
    post = seen[-1]
    assert post.method == "POST" and post.url.path == "/api/seats/hold"
    assert post.headers["authorization"] == "Bearer user-access-token"
    assert post.headers["idempotency-key"]
    assert len(llm.requests) == 1


def test_every_answer_has_the_seat_hold_field_even_with_the_flag_off():
    r = ask(make_client(tool("out_of_scope", reason="x")))
    assert r.json()["seatHold"] is None
