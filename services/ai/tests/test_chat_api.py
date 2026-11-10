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
