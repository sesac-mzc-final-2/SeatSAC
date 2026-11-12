"""POST /ai/chat (AI-01 · 계약 contracts/openapi/ai.openapi.yaml).

① 사용 한도(동시 1·분당 10·하루 100)로 감싼 뒤 오케스트레이터(llm/orchestrator.py)에 넘긴다.

sessionId: 후속 질문 문맥(ai:session:{userId}:{sessionId}, 30분)은
오케스트레이터가 ai Valkey에 둔다.
모르는·남의·만료된 sessionId면 새로 발급한다(오류 아님). Valkey가 없으면(local) 매번 새로 발급.
"""

from __future__ import annotations

from contextlib import nullcontext
from typing import Annotated, Any
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, Field, field_validator

from seatsac_ai.api.deps import current_user_id
from seatsac_ai.common.errors import llm_unavailable, snapshot_not_ready

router = APIRouter(prefix="/ai", tags=["ai"])


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=300)
    sessionId: UUID | None = None

    @field_validator("message")
    @classmethod
    def _not_blank(cls, v: str) -> str:
        # 계약: 공백만인 질문도 400(LENGTH). 한도·Claude 호출 전에 막는다(#93 리뷰)
        if not v.strip():
            raise ValueError("blank")
        return v


@router.post("/chat")
async def chat(
    body: ChatRequest, request: Request, user_id: Annotated[str, Depends(current_user_id)]
) -> dict[str, Any]:
    state = request.app.state
    limiter = state.limiter
    if limiter is None and state.settings.app_env == "staging":
        # staging 에서 한도를 확인할 수 없으면 Claude를 부르지 않는다(비용 보호)
        raise llm_unavailable("QUOTA_STORE_UNAVAILABLE")
    # 스냅샷 준비를 한도보다 먼저 본다. 한도는 Claude 비용을 지키는 장치라,
    # Claude를 부르지 않고 끝나는 503(AI_SNAPSHOT_NOT_READY)은 분당·하루 횟수에서 빼지 않는다.
    if state.chat is None or not state.chat.ready():
        raise snapshot_not_ready()
    async with limiter.acquire(user_id) if limiter else nullcontext():
        result = await state.chat.answer(
            body.message,
            user_id=user_id,
            session_id=str(body.sessionId) if body.sessionId else None,
            # "내 예매"는 사용자 토큰 그대로 공개 API를 부른다(gateway가 Authorization 을 넘긴다)
            authorization=request.headers.get("authorization"),
        )
    session_id = result.pop("sessionId", None) or str(uuid4())
    return {"sessionId": session_id, **result}
