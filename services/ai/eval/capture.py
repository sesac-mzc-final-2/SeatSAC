"""평가용 스냅샷 고정 (WBS 3.4.11).

공개 API(PUBLIC_API_BASE_URL)에서 서비스와 **같은 적재기**로 스냅샷을 만든 뒤 표 3개를 JSON으로 남긴다.
평가(run.py)는 이 파일과 그때의 시각(now)으로 돌려 매번 같은 정답을 얻는다.

    cd services/ai
    uv run python -m eval.capture https://<배포 도메인>/api            # → ~/.seatsac/ai-eval-snapshot.json

⚠️ 회차는 롯데 편성 시드에서 나온 데이터라 **레포에 커밋하지 않는다**(data-model 5장·6-1, #97 리뷰).
기본 위치는 레포 밖 `~/.seatsac/ai-eval-snapshot.json`(환경 변수 SEATSAC_EVAL_SNAPSHOT 로 바꿀 수 있음),
팀 공유본은 비공개 lotte-seed 에 둔다. `.gitignore` 가 `services/ai/eval/snapshot*.json` 을 막는다.
개인 정보는 없다.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
from datetime import datetime
from pathlib import Path

from seatsac_ai.snapshot import schema
from seatsac_ai.snapshot.client import PublicApiClient
from seatsac_ai.snapshot.loader import SnapshotLoader
from seatsac_ai.snapshot.mapper import KST
from seatsac_ai.snapshot.store import SnapshotStore

DEFAULT_SNAPSHOT = Path(
    os.environ.get("SEATSAC_EVAL_SNAPSHOT", "~/.seatsac/ai-eval-snapshot.json")
).expanduser()
MISSING_SNAPSHOT = (
    "평가 스냅샷이 없습니다: {path}\n"
    "  비공개 lotte-seed 에서 받아 그 위치에 두거나, "
    "`uv run python -m eval.capture <공개 API 주소>` 로 새로 만드세요."
)

TABLES = {
    "movie": schema.MOVIE_COLUMNS,
    "theater": schema.THEATER_COLUMNS,
    "screening": schema.SCREENING_COLUMNS,
}


async def capture(base_url: str, out: Path) -> None:
    now = datetime.now(KST).replace(second=0, microsecond=0)
    client = PublicApiClient(base_url)
    store = SnapshotStore()
    loader = SnapshotLoader(client, store, days=7, clock=lambda: now)
    try:
        await loader.refresh_other()
        await loader.refresh_today()
    finally:
        await client.aclose()
    data: dict = {"now": now.isoformat(timespec="minutes"), "tables": {}}
    with store.reader() as conn:
        for table, columns in TABLES.items():
            rows = conn.execute(f"SELECT {', '.join(columns)} FROM {table}").fetchall()  # noqa: S608
            data["tables"][table] = {"columns": list(columns), "rows": [list(r) for r in rows]}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=None, separators=(",", ":")))
    sizes = {t: len(v["rows"]) for t, v in data["tables"].items()}
    print(f"{out}: now={data['now']} {sizes}")


def load_store(path: Path) -> tuple[SnapshotStore, datetime]:
    """capture 가 남긴 파일 → 잠긴 스냅샷 + 그때 시각."""
    data = json.loads(path.read_text())
    store = SnapshotStore()
    inserts = {
        "movie": schema.MOVIE_INSERT,
        "theater": schema.THEATER_INSERT,
        "screening": schema.SCREENING_INSERT,
    }

    def fill(conn) -> None:
        for table, sql in inserts.items():
            t = data["tables"][table]
            conn.executemany(sql, [dict(zip(t["columns"], r, strict=True)) for r in t["rows"]])

    store.update("other", ("movie", "theater", "screening"), fill)
    store.update("today", ("screening",), lambda conn: None)
    return store, datetime.fromisoformat(data["now"])


if __name__ == "__main__":
    out = Path(sys.argv[2]).expanduser() if len(sys.argv) > 2 else DEFAULT_SNAPSHOT
    asyncio.run(capture(sys.argv[1], out))
