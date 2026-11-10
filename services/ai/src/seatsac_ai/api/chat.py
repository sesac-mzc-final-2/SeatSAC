"""POST /ai/chat (AI-01 · 계약 contracts/openapi/ai.openapi.yaml).

① 사용 한도(동시 1·분당 10·하루 100)로 감싼 뒤 오케스트레이터(llm/orchestrator.py)에 넘긴다.

sessionId: 후속 질문 문맥(ai:session:{userId}:{sessionId})은 3.4.5에서 ai Valkey와 함께 만든다.
그 전에는 매번 새로 발급한다 — 계약의 "모르는 sessionId면 새 세션으로 시작(오류 아님)"과 같다.
"""

from __future__ import annotations

from contextlib import nullcontext
from typing import Annotated, Any
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field

from seatsac_ai.api.deps import current_user_id
from seatsac_ai.common.errors import llm_unavailable, snapshot_not_ready

router = APIRouter(prefix="/ai", tags=["ai"])


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=300)
    sessionId: UUID | None = None


@router.post("/chat")
async def chat(
    body: ChatRequest, request: Request, user_id: Annotated[str, Depends(current_user_id)]
) -> dict[str, Any]:
    state = request.app.state
    limiter = state.limiter
    if limiter is None and state.settings.app_env == "staging":
        # staging 에서 한도를 확인할 수 없으면 Claude를 부르지 않는다(비용 보호)
        raise llm_unavailable("QUOTA_STORE_UNAVAILABLE")
    async with limiter.acquire(user_id) if limiter else nullcontext():
        if state.chat is None:
            raise snapshot_not_ready()
        result = await state.chat.answer(body.message)
    return {"sessionId": str(uuid4()), **result}
