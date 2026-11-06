from __future__ import annotations

from typing import Annotated

from fastapi import Header

from seatsac_ai.common.errors import unauthorized


def current_user_id(x_user_id: Annotated[str | None, Header()] = None) -> str:
    """gateway가 검증 뒤 넣어 주는 X-User-Id(= Cognito sub). 없으면 401.

    외부에서 들어온 X-User-*는 gateway가 지우거나 덮어쓰므로 ai는 이 값을 그대로 믿는다.
    """
    if not x_user_id:
        raise unauthorized("TOKEN_REQUIRED")
    return x_user_id
