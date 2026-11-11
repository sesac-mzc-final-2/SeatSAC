"""GET /ai/presets (AI-04): 화면의 추천 질문 버튼 목록 { items: [{presetId, title, example}] }.

버튼을 누르면 화면이 example 문장을 그대로 POST /ai/chat 의 message 로 보낸다.
프리셋 원본은 resources/presets.yaml (3.4.4).

영화 예시 3개(어디서·다음 회차·몇 분)는 {movie} 를 스냅샷에서 지금 상영 회차가 가장 많은
영화로 채운다.
고정 제목을 쓰면 실제 편성에 없는 영화가 버튼에 나가고, 누르면 Claude가 재요청을 3번까지 쓰다
REJECTED 로 끝난다(#92 리뷰). 스냅샷 준비 전이면 "영화 제목"으로 둔다 — 이 API는 503을 내지 않는다.
"""

from __future__ import annotations

from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from seatsac_ai.api.deps import current_user_id
from seatsac_ai.resources import fill_example, presets
from seatsac_ai.snapshot.mapper import KST
from seatsac_ai.snapshot.store import SnapshotNotReady

router = APIRouter(prefix="/ai", tags=["ai"])

_DEMO_MOVIE_SQL = """
SELECT m.title FROM screening s JOIN movie m ON m.movie_id = s.movie_id
WHERE s.starts_at > ?
GROUP BY m.movie_id ORDER BY count(*) DESC, m.title LIMIT 1
"""


class PresetItem(BaseModel):
    presetId: str
    title: str
    example: str


class PresetList(BaseModel):
    items: list[PresetItem]


def showing_movie(request: Request) -> str | None:
    """지금 이후 회차가 가장 많은 영화 제목. 스냅샷이 없거나 준비 전이면 None."""
    snapshot = getattr(request.app.state, "snapshot", None)
    if snapshot is None or not snapshot.store.is_ready():
        return None
    now = f"{datetime.now(KST):%Y-%m-%d %H:%M}"
    try:
        with snapshot.store.reader() as conn:
            row = conn.execute(_DEMO_MOVIE_SQL, (now,)).fetchone()
    except SnapshotNotReady:
        return None
    return row[0] if row else None


@router.get("/presets")
async def list_presets(
    request: Request, _: Annotated[str, Depends(current_user_id)]
) -> PresetList:
    movie = showing_movie(request)
    items = [
        PresetItem(presetId=p.preset_id, title=p.title, example=fill_example(p.example, movie))
        for p in presets()
    ]
    return PresetList(items=items)
