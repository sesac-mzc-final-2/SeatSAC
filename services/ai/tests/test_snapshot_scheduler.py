import asyncio

from seatsac_ai.snapshot import scheduler as sched_mod
from seatsac_ai.snapshot.scheduler import SnapshotScheduler


def test_retries_fast_until_first_success_then_runs_periodically(monkeypatch):
    delays = []
    real_sleep = asyncio.sleep

    async def fake_sleep(d):
        delays.append(d)
        await real_sleep(0)

    monkeypatch.setattr(sched_mod.asyncio, "sleep", fake_sleep)
    calls = {"n": 0}

    async def job():
        calls["n"] += 1
        if calls["n"] <= 2:
            raise RuntimeError("down")

    async def idle():
        await real_sleep(3600)

    async def main():
        s = SnapshotScheduler(job, idle, today_interval=60, other_interval=600)
        s.start()
        for _ in range(20):
            await real_sleep(0)
        await s.stop()

    asyncio.run(main())
    assert delays[:2] == [15, 15]
    assert 60 in delays[2:]
    assert calls["n"] >= 4


def test_stop_cancels_and_start_is_idempotent():
    async def forever():
        await asyncio.sleep(3600)

    async def main():
        s = SnapshotScheduler(forever, forever, 1, 1)
        s.start()
        s.start()
        assert len(s._tasks) == 2
        await s.stop()
        assert s._tasks == []

    asyncio.run(main())
