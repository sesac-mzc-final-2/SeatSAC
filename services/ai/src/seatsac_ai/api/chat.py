"""POST /ai/chat (AI-01). 요청 검증과 계약 형태만 있는 자리표시자.

실제 처리 순서(한도 → 개인 질문 → 정규화 → 정확 일치 → 의미 캐시 → Claude → 실행)는
3.4.3 오케스트레이터가 이 핸들러를 교체한다. 그 전까지는 스냅샷이 없는 상태와 같으므로
503 AI_SNAPSHOT_NOT_READY를 돌려준다(계약에 있는 오류).
"""

from __future__ import annotations

from typing import Annotated, NoReturn
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from seatsac_ai.api.deps import current_user_id
from seatsac_ai.common.errors import snapshot_not_ready

router = APIRouter(prefix="/ai", tags=["ai"])


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=300)
    sessionId: UUID | None = None


@router.post("/chat", response_model=None)
async def chat(body: ChatRequest, user_id: Annotated[str, Depends(current_user_id)]) -> NoReturn:
    raise snapshot_not_ready()
