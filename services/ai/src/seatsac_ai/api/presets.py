"""GET /ai/presets (AI-04): 화면의 추천 질문 버튼 목록 { items: [{presetId, title, example}] }.

버튼을 누르면 화면이 example 문장을 그대로 POST /ai/chat 의 message 로 보낸다.
프리셋 원본은 resources/presets.yaml (3.4.4).

영화 예시 3개(어디서·다음 회차·몇 분)는 {movie} 를 스냅샷에서 지금 이후 회차가 가장 많은
영화로 채운다.
고정 제목을 쓰면 실제 편성에 없는 영화가 버튼에 나가고, 누르면 Claude가 재요청을 3번까지 쓰다
REJECTED 로 끝난다(#92 리뷰).
- 스냅샷 준비 전: "영화 제목"으로 둔다(채팅도 AI_SNAPSHOT_NOT_READY라 눌러도 Claude까지 가지 않는다)
- 준비됐는데 이후 회차가 없음: 영화 버튼 3개를 뺀다("영화 제목"이 Claude로 가지 않게)
이 API는 503을 내지 않는다.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from functools import lru_cache
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from seatsac_ai.api.deps import current_user_id
from seatsac_ai.resources import fill_example, presets
from seatsac_ai.snapshot.store import SnapshotNotReady
from seatsac_ai.sql.executor import SqlExecutionError, execute
from seatsac_ai.sql.validator import ValidatedSql, validate

router = APIRouter(prefix="/ai", tags=["ai"])

_DEMO_MOVIE_SQL = """
SELECT m.title FROM screening s JOIN movie m ON m.movie_id = s.movie_id
WHERE s.starts_at > :now
GROUP BY m.movie_id ORDER BY count(*) DESC, m.title LIMIT 1
"""


class PresetItem(BaseModel):
    presetId: str
    title: str
    example: str


class PresetList(BaseModel):
    items: list[PresetItem]


@dataclass(frozen=True)
class ShowingMovie:
    ready: bool  # 스냅샷을 읽었는가
    title: str | None  # 지금 이후 회차가 가장 많은 영화(없으면 None)


_NOT_READY = ShowingMovie(ready=False, title=None)


@lru_cache(maxsize=1)
def _demo_movie_query() -> ValidatedSql:
    return validate(_DEMO_MOVIE_SQL)


def showing_movie(request: Request) -> ShowingMovie:
    """지금(스토어 시계) 이후 회차가 가장 많은 영화. 동기 SQLite라 to_thread로 부른다."""
    snapshot = getattr(request.app.state, "snapshot", None)
    if snapshot is None or not snapshot.store.is_ready():
        return _NOT_READY
    now = f"{snapshot.store.now():%Y-%m-%d %H:%M}"
    try:
        # 스냅샷 연결은 /ai/chat 과 함께 쓰므로 실행기(execute: 잠금 + 시간 제한)로 부른다.
        # 잠금 밖에서 쓰면 /ai/chat 의 5초 중단에 끊길 수 있었다(#101 리뷰)
        result = execute(snapshot.store, _demo_movie_query(), {"now": now}, max_rows=1)
    except (SnapshotNotReady, SqlExecutionError):
        # 그래도 실패하면 준비 전처럼 둔다 — 버튼 목록은 500을 내지 않는다
        return _NOT_READY
    return ShowingMovie(ready=True, title=result.rows[0][0] if result.rows else None)


@router.get("/presets")
async def list_presets(request: Request, _: Annotated[str, Depends(current_user_id)]) -> PresetList:
    # 이벤트 루프(uvicorn 워커 1개)를 막지 않게 스레드에서 실행한다(#92 리뷰)
    movie = await asyncio.to_thread(showing_movie, request)
    no_movie_to_offer = movie.ready and movie.title is None
    items = [
        PresetItem(
            presetId=p.preset_id, title=p.title, example=fill_example(p.example, movie.title)
        )
        for p in presets()
        if not (no_movie_to_offer and "movie" in p.params)
    ]
    return PresetList(items=items)
