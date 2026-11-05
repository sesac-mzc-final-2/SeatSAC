"""traceId·userId 문맥. Java `TraceIds`·`TraceIdFilter`(libs/common-*)와 같은 규칙.

형식은 소문자 16진수 32자리(error.v1.json pattern). 0만 32개인 값은 무효로 본다(W3C trace-id 규칙).
"""

from __future__ import annotations

import re
import uuid
from contextvars import ContextVar

TRACE_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")
_ALL_ZERO = "0" * 32
TRACE_ID_HEADER = "X-Trace-Id"
USER_ID_HEADER = "X-User-Id"

trace_id_var: ContextVar[str | None] = ContextVar("trace_id", default=None)
user_id_var: ContextVar[str | None] = ContextVar("user_id", default=None)


def new_trace_id() -> str:
    """UUID v4에서 '-'를 뺀 32자리. 잘라 쓰지 않는다."""
    return uuid.uuid4().hex


def is_valid_trace_id(value: str | None) -> bool:
    if value is None or TRACE_ID_PATTERN.fullmatch(value) is None:
        return False
    return value != _ALL_ZERO


def resolve_trace_id(inbound: str | None) -> str:
    """빈 값·대문자·하이픈 붙은 UUID·전부 0·외부에서 주입한 문자열은 버리고 새로 만든다."""
    return inbound if is_valid_trace_id(inbound) else new_trace_id()


def current_trace_id() -> str:
    """요청 밖(기동·백그라운드 작업)에서도 항상 유효한 값을 돌려준다."""
    return trace_id_var.get() or new_trace_id()
