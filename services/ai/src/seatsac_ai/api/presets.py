"""GET /ai/presets (AI-04): 화면의 추천 질문 버튼 목록 { items: [{presetId, title, example}] }.

프리셋 12개와 presets.yaml은 3.4.4에서 채운다. 그 전에는 빈 목록이다.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from seatsac_ai.api.deps import current_user_id

router = APIRouter(prefix="/ai", tags=["ai"])


class PresetItem(BaseModel):
    presetId: str
    title: str
    example: str


class PresetList(BaseModel):
    items: list[PresetItem]


@router.get("/presets")
async def list_presets(_: Annotated[str, Depends(current_user_id)]) -> PresetList:
    return PresetList(items=[])
