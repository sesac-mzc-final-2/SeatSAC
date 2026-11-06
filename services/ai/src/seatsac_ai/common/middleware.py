"""요청마다: traceId 확정 → userId 문맥 → 처리 → 응답 헤더 → 지표 → 접근 로그.

처리 안 된 예외는 여기서 500 INTERNAL_ERROR(error.v1.json 형식)로 바꾼다.
그래야 이 경우에도 X-Trace-Id 헤더·지표·로그가 빠지지 않는다.
"""

from __future__ import annotations

import logging
import time

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

from seatsac_ai.common.errors import ErrorCode, error_response
from seatsac_ai.common.metrics import record_http_request
from seatsac_ai.common.trace import (
    TRACE_ID_HEADER,
    USER_ID_HEADER,
    resolve_trace_id,
    trace_id_var,
    user_id_var,
)

log = logging.getLogger("seatsac_ai.access")


def _uri_label(request: Request) -> str:
    """라우트 템플릿을 쓴다(값이 바뀌는 경로로 라벨이 늘지 않게). 없으면 Spring과 같은 NOT_FOUND."""
    route = request.scope.get("route")
    return getattr(route, "path", None) or "NOT_FOUND"


class RequestContextMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        trace_token = trace_id_var.set(resolve_trace_id(request.headers.get(TRACE_ID_HEADER)))
        user_token = user_id_var.set(request.headers.get(USER_ID_HEADER))
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

            # 프로브·메트릭 수집이 로그를 덮지 않게, /actuator는 오류일 때만 남긴다
            if not request.url.path.startswith("/actuator") or response.status_code >= 400:
                log.info(
                    "%s %s %s",
                    request.method,
                    request.url.path,
                    response.status_code,
                    extra={
                        "fields": {
                            "method": request.method,
                            "uri": uri,
                            "status": response.status_code,
                            "latencyMs": round(elapsed * 1000),
                        }
                    },
                )
            return response
        finally:
            trace_id_var.reset(trace_token)
            user_id_var.reset(user_token)
