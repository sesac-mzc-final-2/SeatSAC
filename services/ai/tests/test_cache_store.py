"""ai Valkey가 흔들릴 때의 캐시 저장소 (런북 6-G-7 · #114 리뷰).

- 재부팅으로 의미 캐시 인덱스가 사라지면 파드를 다시 띄우지 않아도 스스로 다시 만든다.
- 연결 재시도는 기다림 없이 1번 — 멈춘 Valkey 때문에 요청 기한(25초)을 넘기지 않는다.
"""

import asyncio
import time

import fakeredis.aioredis
import pytest
from redis.backoff import NoBackoff
from redis.exceptions import ConnectionError as ValkeyConnectionError
from redis.exceptions import ResponseError

from seatsac_ai.cache.store import CacheEntry, ChatCache, MemoryIndex, index_missing
from seatsac_ai.quota.limiter import connect_valkey

MISSING = "Index with name 'ai_semcache_idx' not found"
ENTRY = CacheEntry(
    origin="PRESET",
    preset_id="today_after_time",
    sql="SELECT 1",
    subject="오늘 {time} 이후 회차",
    kinds=("time",),
    relative_words=("오늘",),
    anchors=(),
    template="오늘 <time> 이후 영화",
)
VECTOR = [1.0, 0.0, 0.0]


class RebootableIndex(MemoryIndex):
    """reboot() 하면 진짜 Valkey 재부팅처럼 인덱스·문서가 사라지고, ensure() 가 다시 만든다."""

    def __init__(self) -> None:
        super().__init__()
        self.exists = False
        self.ensured = 0

    def reboot(self) -> None:
        self.exists = False
        self.docs.clear()

    def _check(self) -> None:
        if not self.exists:
            raise ResponseError(MISSING)

    async def ensure(self) -> None:
        self.ensured += 1
        self.exists = True

    async def count(self) -> int:
        self._check()
        return await super().count()

    async def add(self, doc_id, vector, kinds, entry, ttl) -> None:
        self._check()
        await super().add(doc_id, vector, kinds, entry, ttl)

    async def nearest(self, vector, kinds):
        self._check()
        return await super().nearest(vector, kinds)


class Embed:
    def embed(self, text: str) -> list[float]:
        return VECTOR


def make_cache(index) -> ChatCache:
    valkey = fakeredis.aioredis.FakeRedis(decode_responses=True)
    return ChatCache(
        valkey, ttl=3600, session_ttl=1800, threshold=0.92, embedder=Embed(), index=index
    )


def run(coro):
    return asyncio.run(coro)


def test_index_missing_only_matches_the_missing_index_error():
    assert index_missing(ResponseError(MISSING))
    assert index_missing(ResponseError("ai_semcache_idx: no such index"))
    # 모듈 없음 — 다시 만들 수 없다
    assert not index_missing(ResponseError("unknown command 'FT.SEARCH'"))
    # 연결 오류는 다른 길(재시도 시각)로 다룬다
    assert not index_missing(ValkeyConnectionError("Index not found"))


def test_search_after_reboot_rebuilds_the_index_and_next_put_is_found():
    async def scenario():
        index = RebootableIndex()
        cache = make_cache(index)
        await cache.start()
        await cache.put("q1", ENTRY, VECTOR)
        assert (await cache.nearest(VECTOR, ENTRY.kinds)) is not None

        index.reboot()  # 인덱스·문서가 사라짐
        assert (await cache.nearest(VECTOR, ENTRY.kinds)) is None  # 이번 질문은 놓침
        assert cache.semantic_ready and index.ensured == 2  # 파드 재시작 없이 다시 만들었다

        await cache.put("q1", ENTRY, VECTOR)
        found = await cache.nearest(VECTOR, ENTRY.kinds)
        assert found is not None and found[0].preset_id == "today_after_time"

    run(scenario())


def test_put_after_reboot_rebuilds_and_still_saves_the_vector():
    async def scenario():
        index = RebootableIndex()
        cache = make_cache(index)
        await cache.start()
        index.reboot()

        await cache.put("q1", ENTRY, VECTOR)  # count() 가 「인덱스 없음」 → 다시 만들고 저장

        assert index.ensured == 2 and len(index.docs) == 1
        assert (await cache.get_exact("q1")) is not None  # 정확 일치 캐시는 인덱스와 무관
        assert (await cache.nearest(VECTOR, ENTRY.kinds)) is not None

    run(scenario())


def test_other_search_errors_do_not_rebuild():
    class Broken(RebootableIndex):
        async def nearest(self, vector, kinds):
            raise ResponseError("Invalid filter format")

    async def scenario():
        index = Broken()
        cache = make_cache(index)
        await cache.start()
        assert (await cache.nearest(VECTOR, ENTRY.kinds)) is None
        assert index.ensured == 1 and cache.semantic_ready

    run(scenario())


def test_valkey_client_retries_once_without_waiting():
    client = connect_valkey("127.0.0.1", 1, tls=False)
    retry = client.get_retry()
    assert retry.get_retries() == 1
    assert isinstance(retry._backoff, NoBackoff)


def test_command_against_a_dead_valkey_fails_fast():
    """아무도 듣지 않는 포트 — redis-py 기본값(10번 + 백오프)이면 몇 초~수십 초 걸린다."""

    async def scenario():
        client = connect_valkey("127.0.0.1", 1, tls=False)
        started = time.perf_counter()
        with pytest.raises(ValkeyConnectionError):
            await client.set("k", "v")
        await client.aclose()
        return time.perf_counter() - started

    assert run(scenario()) < 2.5  # 상한 1초 × 2번
