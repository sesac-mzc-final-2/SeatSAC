"""요청마다: traceId 확정 → 처리 → 응답 헤더 → 지표 → 접근 로그(ai와 같은 순서).

ops-api는 gateway를 거치지 않는다 → 바깥 X-Trace-Id는 형식이 맞을 때만 쓰고(resolve_trace_id),
X-User-Id 헤더는 믿지 않는다(userId는 토큰 sub — auth.py). 오류 응답 로그에는
errorCategory(ADR-121)를 붙인다.
"""

from __future__ import annotations

import logging
import time

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from seatsac_ops.common.errors import ErrorCode, error_response
from seatsac_ops.common.metrics import record_http_request
from seatsac_ops.common.trace import TRACE_ID_HEADER, resolve_trace_id, trace_id_var

log = logging.getLogger("seatsac_ops.access")


def _uri_label(request: Request) -> str:
    route = request.scope.get("route")
    return getattr(route, "path", None) or "NOT_FOUND"


def error_category(status: int) -> str | None:
    """conventions 6장 errorCategory. 401·403은 SECURITY(관리자 API를 두드린 기록)."""
    if status in (401, 403):
        return "SECURITY"
    if status == 400:
        return "VALIDATION"
    if status == 409:
        return "CONFLICT"
    if status == 503:
        return "HTTP_UPSTREAM"
    if status >= 500:
        return "INTERNAL"
    return None


class RequestContextMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        token = trace_id_var.set(resolve_trace_id(request.headers.get(TRACE_ID_HEADER)))
        started = time.perf_counter()
        try:
            try:
                response = await call_next(request)
            except Exception:
                log.exception("unhandled error")
                response = error_response(500, ErrorCode.INTERNAL_ERROR, "Internal server error")

            elapsed = time.perf_counter() - started
            response.headers[TRACE_ID_HEADER] = trace_id_var.get() or ""
            uri = _uri_label(request)
            record_http_request(uri, response.status_code, request.method, elapsed)
            if not request.url.path.startswith("/actuator") or response.status_code >= 400:
                fields: dict[str, object] = {
                    "method": request.method,
                    "uri": uri,
                    "status": response.status_code,
                    "latencyMs": round(elapsed * 1000),
                }
                if (category := error_category(response.status_code)) is not None:
                    fields["errorCategory"] = category
                log.info(
                    "%s %s %s",
                    request.method,
                    request.url.path,
                    response.status_code,
                    extra={"fields": fields},
                )
            return response
        finally:
            trace_id_var.reset(token)
