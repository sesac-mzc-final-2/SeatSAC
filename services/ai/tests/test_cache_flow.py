"""캐시 → Claude 순서 끝에서 끝까지 (3.4.5).

가짜 Claude · fakeredis · 메모리 벡터 인덱스 · 가짜 임베딩.
"""

import asyncio
from datetime import datetime, timedelta

import fakeredis
from redis.exceptions import ConnectionError as RedisConnectionError

from seatsac_ai.cache.store import ChatCache, MemoryIndex
from seatsac_ai.config import Settings
from seatsac_ai.llm.orchestrator import ChatService
from seatsac_ai.snapshot.mapper import KST

from .llm_fakes import ScriptedLlm, tool
from .sql_fixtures import loaded_store

NOW = datetime(2026, 11, 17, 19, 40, tzinfo=KST)
USER = "user-1"
AFTER_20_SQL = (
    "SELECT m.title AS movie_title, s.starts_at FROM screening s "
    "JOIN movie m ON m.movie_id = s.movie_id "
    "WHERE s.business_date = '2026-11-17' AND s.starts_at >= '2026-11-17 20:00' "
    "AND s.starts_at > '2026-11-17 19:40' ORDER BY s.starts_at"
)


class FakeEmbedder:
    """정해 둔 템플릿 질문 → 벡터. 나머지는 서로 먼 벡터."""

    def __init__(self, table=None):
        self.table = table or {}
        self.calls = 0

    def embed(self, text):
        self.calls += 1
        if text in self.table:
            return self.table[text]
        h = abs(hash(text)) % 997
        return [1.0, float(h), float(h % 7), float(h % 13)]


def make(llm, embedder=None, valkey=None, **settings):
    cache = ChatCache(
        valkey or fakeredis.FakeAsyncRedis(decode_responses=True),
        ttl=3600,
        session_ttl=1800,
        threshold=0.92,
        embedder=embedder or FakeEmbedder(),
        index=MemoryIndex(),
    )
    asyncio.run(cache.start())
    service = ChatService(
        loaded_store(), llm, Settings(_env_file=None, **settings), clock=lambda: NOW, cache=cache
    )
    return service, cache


def ask(service, question, now=NOW, session_id=None, user=USER):
    return asyncio.run(service.answer(question, now=now, user_id=user, session_id=session_id))


def preset_after(time):
    return tool("run_preset", presetId="today_after_time", params={"time": time})


# --- 정확 일치 ---------------------------------------------------------------


def test_same_question_second_time_is_exact_hit_with_zero_tokens():
    llm = ScriptedLlm(preset_after("20:00"))
    service, _ = make(llm)
    first = ask(service, "오늘 20시 이후 영화 알려줘")
    second = ask(service, "오늘 20시 이후 영화 알려줘")
    assert first["path"] == "PRESET" and first["llm"]["calls"] == 1
    assert second["path"] == "CACHE_EXACT" and second["llm"]["calls"] == 0
    assert second["llm"]["inputTokens"] == 0 and second["rows"] == first["rows"]
    assert second["presetId"] == "today_after_time"
    assert len(llm.requests) == 1  # Claude는 한 번만


def test_different_slot_value_reuses_the_template_with_new_value():
    service, _ = make(ScriptedLlm(preset_after("20:00")))
    ask(service, "오늘 20시 이후 영화 알려줘")
    again = ask(service, "오늘 21시 이후 영화 알려줘")
    assert again["path"] == "CACHE_EXACT" and again["rowCount"] == 3  # 20시는 4건
    assert "'21:00'" in again["sql"] and ":time" not in again["sql"]


def test_nl2sql_answer_is_cached_as_a_template():
    llm = ScriptedLlm(tool("run_sql", sql=AFTER_20_SQL, explanation="오늘 20시 이후 회차"))
    service, _ = make(llm)
    assert ask(service, "오늘 20시 넘어서 시작하는 영화 제목")["path"] == "NL2SQL"
    hit = ask(service, "오늘 21시 넘어서 시작하는 영화 제목")
    assert hit["path"] == "CACHE_EXACT" and hit["rowCount"] == 3
    assert hit["answer"].startswith("오늘 21시 이후 회차: 3건입니다.")


def test_cached_claude_sql_still_drops_screenings_that_already_started():
    """Claude SQL엔 실행할 때 `starts_at > :now` 를 붙인다(#93). 캐시로 답할 때도 똑같이."""
    no_filter = (
        "SELECT m.title AS movie_title, s.starts_at FROM screening s "
        "JOIN movie m ON m.movie_id = s.movie_id "
        "WHERE s.business_date = '2026-11-17' AND s.starts_at >= '2026-11-17 20:00' "
        "ORDER BY s.starts_at"
    )
    llm = ScriptedLlm(tool("run_sql", sql=no_filter, explanation="오늘 20시 이후 회차"))
    service, _ = make(llm)
    first = ask(service, "오늘 20시 넘어서 시작하는 영화 제목")
    assert first["path"] == "NL2SQL" and first["rowCount"] == 4
    later = NOW.replace(hour=20, minute=30)
    hit = ask(service, "오늘 20시 넘어서 시작하는 영화 제목", now=later)
    assert hit["path"] == "CACHE_EXACT" and hit["llm"]["calls"] == 0
    assert hit["rowCount"] < first["rowCount"]
    assert all(row[1] > "2026-11-17 20:30" for row in hit["rows"])


def test_cached_relative_date_follows_the_day_it_is_asked():
    llm = ScriptedLlm(
        tool("run_preset", presetId="theater_schedule",
             params={"date": "2026-11-18", "theater": "건대입구"}),
    )  # fmt: skip
    service, _ = make(llm)
    first = ask(service, "내일 건대입구 상영표")
    assert first["rowCount"] == 2
    # 하루 뒤 같은 질문 → 캐시를 쓰지만 날짜는 그날의 "내일"(11/19)
    later = ask(service, "내일 건대입구 상영표", now=NOW + timedelta(days=1))
    assert later["path"] == "CACHE_EXACT" and "'2026-11-19'" in later["sql"]


def test_sql_whose_values_cannot_be_explained_is_not_cached():
    by_id = (
        "SELECT s.starts_at FROM screening s WHERE s.theater_id = 20 "
        "AND s.starts_at > '2026-11-17 19:40'"
    )
    llm = ScriptedLlm(tool("run_sql", sql=by_id, explanation="강남 회차"),
                      tool("run_sql", sql=by_id, explanation="강남 회차"))  # fmt: skip
    service, _ = make(llm)
    ask(service, "강남 회차")
    assert ask(service, "강남 회차")["path"] == "NL2SQL"  # 다시 Claude
    assert len(llm.requests) == 2


def test_rejected_and_out_of_scope_are_not_cached():
    llm = ScriptedLlm(tool("out_of_scope", reason="x"), tool("out_of_scope", reason="x"))
    service, _ = make(llm)
    ask(service, "내일 날씨")
    assert ask(service, "내일 날씨")["path"] == "OUT_OF_SCOPE" and len(llm.requests) == 2


# --- 의미 캐시 ---------------------------------------------------------------

A = "오늘 {time} 이후에 시작하는 영화 알려줘"
B = "오늘 {time} 넘어서 하는 영화 보여줘"
C = "내일 {time} 넘어서 하는 영화 보여줘"


def test_similar_question_is_semantic_hit():
    embedder = FakeEmbedder({A: [1.0, 0.0, 0.0], B: [0.97, 0.2, 0.0]})  # 코사인 약 0.98
    service, _ = make(ScriptedLlm(preset_after("20:00")), embedder)
    ask(service, "오늘 20시 이후에 시작하는 영화 알려줘")
    hit = ask(service, "오늘 21시 넘어서 하는 영화 보여줘")
    assert hit["path"] == "CACHE_SEMANTIC" and hit["llm"]["calls"] == 0 and hit["rowCount"] == 3


def test_below_threshold_is_a_miss():
    embedder = FakeEmbedder({A: [1.0, 0.0, 0.0], B: [0.6, 0.8, 0.0]})  # 0.6
    llm = ScriptedLlm(preset_after("20:00"), preset_after("21:00"))
    service, _ = make(llm, embedder)
    ask(service, "오늘 20시 이후에 시작하는 영화 알려줘")
    assert ask(service, "오늘 21시 넘어서 하는 영화 보여줘")["path"] == "PRESET"


def test_today_answer_is_not_reused_for_tomorrow_even_if_similar():
    embedder = FakeEmbedder({A: [1.0, 0.0, 0.0], C: [0.99, 0.1, 0.0]})
    llm = ScriptedLlm(preset_after("20:00"), tool("out_of_scope", reason="x"))
    service, _ = make(llm, embedder)
    ask(service, "오늘 20시 이후에 시작하는 영화 알려줘")
    assert ask(service, "내일 21시 넘어서 하는 영화 보여줘")["path"] == "OUT_OF_SCOPE"


def test_fixed_word_in_sql_must_be_in_the_new_question():
    recliner = (
        "SELECT s.starts_at FROM screening s WHERE s.screen_type = 'RECLINER' "
        "AND s.business_date = '2026-11-17' AND s.starts_at > '2026-11-17 19:40'"
    )
    q1, q2 = "오늘 리클라이너 회차", "오늘 일반관 회차"
    embedder = FakeEmbedder({q1: [1.0, 0.0], q2: [0.99, 0.05]})
    llm = ScriptedLlm(tool("run_sql", sql=recliner, explanation="오늘 리클라이너 회차"),
                      tool("out_of_scope", reason="x"))  # fmt: skip
    service, _ = make(llm, embedder)
    ask(service, q1)
    assert ask(service, q2)["path"] == "OUT_OF_SCOPE"  # 리클라이너 SQL을 일반관 질문에 쓰지 않는다


def test_semantic_cache_off_by_setting():
    embedder = FakeEmbedder({A: [1.0, 0.0], B: [1.0, 0.0]})
    llm = ScriptedLlm(preset_after("20:00"), preset_after("21:00"))
    service, _ = make(llm, embedder, APP_AI_SEMANTIC_CACHE_ENABLED=False)
    ask(service, "오늘 20시 이후에 시작하는 영화 알려줘")
    assert ask(service, "오늘 21시 넘어서 하는 영화 보여줘")["path"] == "PRESET"
    assert embedder.calls == 0


# --- 세션·후속 질문 -------------------------------------------------------------


def test_session_id_is_kept_and_followup_gets_previous_context():
    llm = ScriptedLlm(preset_after("20:00"), tool("run_sql", sql=AFTER_20_SQL, explanation="x"))
    service, _ = make(llm)
    first = ask(service, "오늘 20시 이후 영화 알려줘")
    follow = ask(service, "그중 건대입구만", session_id=first["sessionId"])
    assert follow["sessionId"] == first["sessionId"]
    user_text = llm.requests[1]["messages"][0]["content"]
    assert (
        "직전 질문: 오늘 20시 이후 영화 알려줘" in user_text and "직전 SQL" not in user_text
    )  # 프리셋은 sql null


def test_followup_is_not_answered_from_cache():
    llm = ScriptedLlm(preset_after("20:00"), preset_after("20:00"), preset_after("20:00"))
    service, _ = make(llm)
    first = ask(service, "오늘 20시 이후 영화 알려줘")
    ask(service, "그중 20시 이후만", session_id=first["sessionId"])
    again = ask(service, "그중 20시 이후만", session_id=first["sessionId"])
    assert again["path"] == "PRESET" and len(llm.requests) == 3


def test_someone_elses_session_id_starts_a_new_session():
    service, _ = make(ScriptedLlm(preset_after("20:00"), preset_after("20:00")))
    mine = ask(service, "오늘 20시 이후 영화 알려줘")
    other = ask(service, "그중 건대입구만", session_id=mine["sessionId"], user="user-2")
    assert other["sessionId"] != mine["sessionId"]


# --- Valkey 장애 ---------------------------------------------------------------


class BrokenValkey:
    async def get(self, *a, **k):
        raise RedisConnectionError("down")

    async def set(self, *a, **k):
        raise RedisConnectionError("down")


def test_valkey_failure_falls_back_to_claude():
    llm = ScriptedLlm(preset_after("20:00"))
    service, _ = make(llm, valkey=BrokenValkey())
    body = ask(service, "오늘 20시 이후 영화 알려줘")
    assert body["path"] == "PRESET" and body["rowCount"] == 4


def test_opposite_meaning_is_not_a_semantic_hit():
    """실측: 임베딩은 "이후"와 "전에"를 0.935로 본다. 뜻 묶음 검사로 막는다."""
    before = "오늘 {time} 전에 시작하는 영화 알려줘"
    embedder = FakeEmbedder({A: [1.0, 0.0, 0.0], before: [0.99, 0.1, 0.0]})
    llm = ScriptedLlm(preset_after("20:00"), tool("out_of_scope", reason="x"))
    service, _ = make(llm, embedder)
    ask(service, "오늘 20시 이후에 시작하는 영화 알려줘")
    assert ask(service, "오늘 21시 전에 시작하는 영화 알려줘")["path"] == "OUT_OF_SCOPE"


# --- #94 리뷰 -------------------------------------------------------------------


def test_followup_wording_without_session_is_not_cached():
    """세션이 없어도 "그중 …" 말투는 캐시를 보지도 저장하지도 않는다(문맥 없는 답을 남이 재사용)."""
    llm = ScriptedLlm(preset_after("20:00"), preset_after("20:00"))
    service, _ = make(llm)
    first = ask(service, "그중 20시 이후만")
    second = ask(service, "그중 20시 이후만")
    assert first["path"] == second["path"] == "PRESET" and len(llm.requests) == 2


def test_exact_entry_with_other_slot_kinds_counts_as_miss():
    """슬롯 종류가 달라 버린 정확 일치는 지표에서도 적중이 아니다."""
    from seatsac_ai.cache.store import exact_key
    from seatsac_ai.common.metrics import AI_CACHE_REQUESTS

    llm = ScriptedLlm(preset_after("20:00"), preset_after("20:00"))
    service, cache = make(llm, APP_AI_SEMANTIC_CACHE_ENABLED=False)
    ask(service, "오늘 20시 이후 영화 알려줘")
    ext = service._extract("오늘 20시 이후 영화 알려줘", NOW)
    raw = asyncio.run(cache._v.get(exact_key(ext.key_text)))
    asyncio.run(cache._v.set(exact_key(ext.key_text), raw.replace('"time"', '"date"')))
    hits = AI_CACHE_REQUESTS.labels("exact", "hit")._value.get()
    body = ask(service, "오늘 20시 이후 영화 알려줘")
    assert body["path"] == "PRESET"
    assert AI_CACHE_REQUESTS.labels("exact", "hit")._value.get() == hits


class FlakyIndex(MemoryIndex):
    """처음 ensure()는 연결 오류, 그다음부터 정상."""

    def __init__(self, error):
        super().__init__()
        self.error = error
        self.ensures = 0

    async def ensure(self):
        self.ensures += 1
        if self.ensures == 1:
            raise self.error
        await super().ensure()


def _cache_with(index):
    return ChatCache(
        fakeredis.FakeAsyncRedis(decode_responses=True),
        ttl=3600,
        session_ttl=1800,
        threshold=0.92,
        embedder=FakeEmbedder(),
        index=index,
    )


def test_semantic_index_is_retried_after_a_connection_error(monkeypatch):
    """기동 순간의 연결 오류 한 번으로 파드 수명 내내 의미 캐시가 꺼지지 않는다."""
    from seatsac_ai.cache import store

    monkeypatch.setattr(store, "RETRY_SECONDS", 0)
    cache = _cache_with(FlakyIndex(RedisConnectionError("not yet")))
    asyncio.run(cache.start())
    assert cache.semantic_ready is False
    assert asyncio.run(cache.semantic_available()) is True


def test_missing_search_module_turns_semantic_cache_off_for_good(monkeypatch):
    from redis.exceptions import ResponseError

    from seatsac_ai.cache import store

    monkeypatch.setattr(store, "RETRY_SECONDS", 0)
    index = FlakyIndex(ResponseError("unknown command 'FT.CREATE'"))
    cache = _cache_with(index)
    asyncio.run(cache.start())
    assert asyncio.run(cache.semantic_available()) is False and index.ensures == 1


def test_semantic_entry_is_written_with_ttl_in_one_transaction():
    """HSET과 EXPIRE를 한 트랜잭션으로 — TTL 없는 ai:semcache:* 가 남지 않는다."""
    from seatsac_ai.cache.store import SEMCACHE_PREFIX, ValkeySearchIndex

    valkey = fakeredis.FakeAsyncRedis(decode_responses=False)
    index = ValkeySearchIndex(valkey)
    asyncio.run(index.add("doc1", [0.1] * 384, "time", "{}", 3600))
    ttl = asyncio.run(valkey.ttl(SEMCACHE_PREFIX + "doc1"))
    assert 0 < ttl <= 3600
