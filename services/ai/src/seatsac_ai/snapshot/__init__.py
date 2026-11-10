"""⑦ SQLite 스냅샷 (WBS 3.4.9). 공개 API로 채운 파드 메모리 DB.

흐름: client(가져오기) → mapper(표 한 줄로 변환) → loader(순서 지휘) → store(잠그고 교체) ← 질의
scheduler가 loader를 주기적으로 깨운다. 조립은 `build_snapshot`이 한다.
"""

from __future__ import annotations

from dataclasses import dataclass

from seatsac_ai.common.metrics import AI_SNAPSHOT_AGE_SECONDS
from seatsac_ai.config import Settings
from seatsac_ai.snapshot.client import PublicApiClient
from seatsac_ai.snapshot.loader import SnapshotLoader
from seatsac_ai.snapshot.scheduler import SnapshotScheduler
from seatsac_ai.snapshot.store import SCOPES, SnapshotNotReady, SnapshotStore

__all__ = ["Snapshot", "SnapshotNotReady", "SnapshotStore", "build_snapshot"]


@dataclass
class Snapshot:
    store: SnapshotStore
    scheduler: SnapshotScheduler
    client: PublicApiClient

    def start(self) -> None:
        self.scheduler.start()

    async def stop(self) -> None:
        await self.scheduler.stop()
        await self.client.aclose()
        self.store.close()


def build_snapshot(settings: Settings, base_url: str) -> Snapshot:
    client = PublicApiClient(base_url)
    store = SnapshotStore()
    loader = SnapshotLoader(client, store, days=settings.snapshot_days)
    scheduler = SnapshotScheduler(
        today=loader.refresh_today,
        other=loader.refresh_other,
        today_interval=settings.snapshot_today_interval,
        other_interval=settings.snapshot_other_interval,
    )
    # 스크랩할 때마다 계산한다(마지막 성공 적재 이후 경과 초). 한 번도 못 했으면 기동 이후 경과 초.
    for scope in SCOPES:
        AI_SNAPSHOT_AGE_SECONDS.labels(scope).set_function(lambda s=scope: store.age_seconds(s))
    return Snapshot(store=store, scheduler=scheduler, client=client)
