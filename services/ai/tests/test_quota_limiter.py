"""사용 한도: 동시 1건 · 분당 · 하루 · Valkey 장애 시 거절. fakeredis(Lua 포함)로 서버 없이 돈다."""

import asyncio
from datetime import datetime

import fakeredis
import pytest
from redis.exceptions import ConnectionError as RedisConnectionError

from seatsac_ai.common.errors import ApiError
from seatsac_ai.quota.limiter import QuotaLimiter
from seatsac_ai.snapshot.mapper import KST

NOW = datetime(2026, 11, 17, 19, 40, 15, tzinfo=KST)
USER = "u1"


def limiter(per_minute=10, per_day=100):
    valkey = fakeredis.FakeAsyncRedis(decode_responses=True)
    return QuotaLimiter(valkey, per_minute, per_day), valkey


async def ask(lim, user=USER, now=NOW):
    async with lim.acquire(user, now):
        return "ok"


def test_allows_up_to_the_minute_limit_then_429():
    async def run():
        lim, _ = limiter(per_minute=3)
        for _ in range(3):
            assert await ask(lim) == "ok"
        with pytest.raises(ApiError) as caught:
            await ask(lim)
        return caught.value

    err = asyncio.run(run())
    assert err.status == 429 and err.code == "AI_QUOTA_EXCEEDED"
    assert err.details[0].reason == "PER_MINUTE" and err.details[0].value == "3/min"
    assert err.headers["Retry-After"] == "45"  # 19:40:15 → 다음 분까지 45초


def test_next_minute_is_a_new_window():
    async def run():
        lim, _ = limiter(per_minute=1)
        await ask(lim)
        return await ask(lim, now=NOW.replace(minute=41))

    assert asyncio.run(run()) == "ok"


def test_day_limit_and_retry_after_until_kst_midnight():
    async def run():
        lim, _ = limiter(per_minute=100, per_day=2)
        await ask(lim)
        await ask(lim)
        with pytest.raises(ApiError) as caught:
            await ask(lim)
        return caught.value

    err = asyncio.run(run())
    assert err.details[0].reason == "PER_DAY"
    assert err.headers["Retry-After"] == str(4 * 3600 + 19 * 60 + 45)  # 19:40:15 → 24:00


def test_second_question_while_first_is_running_is_409():
    async def run():
        lim, _ = limiter()
        async with lim.acquire(USER, NOW):
            with pytest.raises(ApiError) as caught:
                await ask(lim)
        return caught.value, await ask(lim)  # 첫 질문이 끝나면 다시 된다

    err, after = asyncio.run(run())
    assert err.status == 409 and err.code == "AI_QUERY_IN_PROGRESS" and after == "ok"


def test_other_users_are_independent():
    async def run():
        lim, _ = limiter(per_minute=1)
        await ask(lim, "a")
        return await ask(lim, "b")

    assert asyncio.run(run()) == "ok"


def test_lock_is_released_when_quota_is_exceeded():
    async def run():
        lim, valkey = limiter(per_minute=0)
        with pytest.raises(ApiError):
            await ask(lim)
        return await valkey.exists(f"ai:inflight:{USER}")

    assert asyncio.run(run()) == 0


def test_late_finisher_does_not_release_someone_elses_lock():
    async def run():
        lim, valkey = limiter()
        async with lim.acquire(USER, NOW):
            # 내 잠금이 만료된 뒤 다음 요청이 같은 키를 잡았다고 가정
            await valkey.set(f"ai:inflight:{USER}", "next-request-token")
        return await valkey.get(f"ai:inflight:{USER}")

    assert asyncio.run(run()) == "next-request-token"


def test_keys_have_ttls():
    async def run():
        lim, valkey = limiter()
        async with lim.acquire(USER, NOW):
            inflight = await valkey.pttl(f"ai:inflight:{USER}")
        minute = await valkey.ttl(f"ai:quota:min:{USER}:202611171940")
        day = await valkey.ttl(f"ai:quota:day:{USER}:20261117")
        return inflight, minute, day

    inflight, minute, day = asyncio.run(run())
    assert 0 < inflight <= 40_000 and 0 < minute <= 60 and 0 < day <= 86_400


class BrokenValkey:
    async def set(self, *a, **k):
        raise RedisConnectionError("down")

    async def eval(self, *a, **k):
        raise RedisConnectionError("down")


def test_valkey_down_rejects_instead_of_calling_claude():
    lim = QuotaLimiter(BrokenValkey(), 10, 100)
    with pytest.raises(ApiError) as caught:
        asyncio.run(ask(lim))
    assert caught.value.status == 503 and caught.value.code == "AI_LLM_UNAVAILABLE"
    assert caught.value.details[0].reason == "QUOTA_STORE_UNAVAILABLE"
