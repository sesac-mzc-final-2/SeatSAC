"""X-Trace-Id 읽기·생성, 응답 헤더 — Java TraceIdFilter 와 같은 규칙."""
import uuid

from starlette.middleware.base import BaseHTTPMiddleware

HEADER = "X-Trace-Id"


class TraceIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        trace_id = request.headers.get(HEADER) or uuid.uuid4().hex[:16]
        request.state.trace_id = trace_id
        response = await call_next(request)
        response.headers[HEADER] = trace_id
        return response
