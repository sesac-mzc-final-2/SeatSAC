"""GET /ai/presets (AI-04): 화면의 추천 질문 버튼 목록 { items: [{presetId, title, example}] }.

버튼을 누르면 화면이 example 문장을 그대로 POST /ai/chat 의 message 로 보낸다.
프리셋 원본은 resources/presets.yaml (3.4.4).
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from seatsac_ai.api.deps import current_user_id
from seatsac_ai.resources import presets

router = APIRouter(prefix="/ai", tags=["ai"])


class PresetItem(BaseModel):
    presetId: str
    title: str
    example: str


class PresetList(BaseModel):
    items: list[PresetItem]


@router.get("/presets")
async def list_presets(_: Annotated[str, Depends(current_user_id)]) -> PresetList:
    items = [PresetItem(presetId=p.preset_id, title=p.title, example=p.example) for p in presets()]
    return PresetList(items=items)
