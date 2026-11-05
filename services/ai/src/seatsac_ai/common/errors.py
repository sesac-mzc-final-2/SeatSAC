"""오류 형식: contracts/schemas/error.v1.json 그대로.

{ code, message, traceId, details[] } — details는 항상 배열, 원소는 { field, value?, reason? }.
ai 코드 4개는 이 서비스 안에서만 정의한다(libs/CommonResponseCode는 바꾸지 않는다).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from seatsac_ai.common.trace import current_trace_id

log = logging.getLogger(__name__)


class ErrorCode(StrEnum):
    # 공통
    VALIDATION_FAILED = "VALIDATION_FAILED"
    UNAUTHORIZED = "UNAUTHORIZED"
    FORBIDDEN = "FORBIDDEN"
    NOT_FOUND = "NOT_FOUND"
    METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    # ai 전용
    AI_QUERY_IN_PROGRESS = "AI_QUERY_IN_PROGRESS"
    AI_QUOTA_EXCEEDED = "AI_QUOTA_EXCEEDED"
    AI_LLM_UNAVAILABLE = "AI_LLM_UNAVAILABLE"
    AI_SNAPSHOT_NOT_READY = "AI_SNAPSHOT_NOT_READY"


@dataclass(frozen=True)
class ErrorDetail:
    field: str | None = None
    value: Any = None
    reason: str | None = None

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        if self.field is not None:
            out["field"] = self.field
        if self.value is not None:
            out["value"] = self.value
        if self.reason is not None:
            out["reason"] = self.reason
        return out


@dataclass
class ApiError(Exception):
    status: int
    code: ErrorCode
    message: str
    details: list[ErrorDetail] = field(default_factory=list)
    headers: dict[str, str] = field(default_factory=dict)

    def __str__(self) -> str:
        return f"{self.status} {self.code}: {self.message}"


def error_response(
    status: int,
    code: ErrorCode,
    message: str,
    details: list[ErrorDetail] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    body = {
        "code": code.value,
        "message": message,
        "traceId": current_trace_id(),
        "details": [d.to_dict() for d in (details or [])],
    }
    return JSONResponse(body, status_code=status, headers=headers)


# --- 자주 쓰는 오류 (15-1, 3-4 표와 같은 HTTP·code·details) -----------------


def unauthorized(reason: str = "TOKEN_REQUIRED") -> ApiError:
    return ApiError(
        401, ErrorCode.UNAUTHORIZED, "Authentication required", [ErrorDetail(reason=reason)]
    )


def query_in_progress() -> ApiError:
    return ApiError(409, ErrorCode.AI_QUERY_IN_PROGRESS, "A previous question is still running")


def quota_exceeded(
    window: Literal["PER_MINUTE", "PER_DAY"], limit: int, retry_after_seconds: int
) -> ApiError:
    value = f"{limit}/min" if window == "PER_MINUTE" else f"{limit}/day"
    return ApiError(
        429,
        ErrorCode.AI_QUOTA_EXCEEDED,
        "Usage limit exceeded",
        [ErrorDetail(field="quota", value=value, reason=window)],
        headers={"Retry-After": str(max(1, retry_after_seconds))},
    )


def llm_unavailable(
    reason: Literal["TIMEOUT", "OVERLOADED", "RATE_LIMITED", "SPEND_LIMIT", "CIRCUIT_OPEN"],
) -> ApiError:
    return ApiError(
        503,
        ErrorCode.AI_LLM_UNAVAILABLE,
        "LLM provider unavailable",
        [ErrorDetail(field="provider", value="anthropic", reason=reason)],
    )


def snapshot_not_ready() -> ApiError:
    return ApiError(503, ErrorCode.AI_SNAPSHOT_NOT_READY, "Snapshot is not loaded yet")


def upstream_unavailable(upstream: str) -> ApiError:
    """ai Valkey 연결 실패(upstream=\"valkey\") 또는 \"내 예매\" 공개 API 실패(\"user\")."""
    return ApiError(
        503,
        ErrorCode.SERVICE_UNAVAILABLE,
        "Upstream unavailable",
        [ErrorDetail(field="upstream", value=upstream)],
    )


# --- 예외 → 응답 ------------------------------------------------------------

_HTTP_STATUS_CODE = {
    401: ErrorCode.UNAUTHORIZED,
    403: ErrorCode.FORBIDDEN,
    404: ErrorCode.NOT_FOUND,
}


def _validation_details(exc: RequestValidationError) -> list[ErrorDetail]:
    """Java GlobalExceptionHandler와 같게: 필드 이름 + 사유만. 사용자 입력값은 담지 않는다."""
    details = []
    for err in exc.errors():
        if err["type"] == "json_invalid":
            # 본문이 깨진 JSON — Java HttpMessageNotReadableException과 같은 사유
            return [ErrorDetail(reason="MALFORMED_BODY")]
        # loc 예: ("body", "message") → field "message"
        path = ".".join(str(p) for p in err["loc"] if p not in ("body", "query", "path", "header"))
        if not path and err["type"] == "missing":
            return [ErrorDetail(reason="MALFORMED_BODY")]  # 본문 자체가 없음
        details.append(ErrorDetail(field=path or None, reason=str(err["type"]).upper()))
    return details


def install_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return error_response(exc.status, exc.code, exc.message, exc.details, exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        return error_response(
            400, ErrorCode.VALIDATION_FAILED, "Request validation failed", _validation_details(exc)
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        if exc.status_code == 405:
            # Java와 같은 모양: details[{field:"method", value:<메서드>, reason:METHOD_NOT_ALLOWED}]
            return error_response(
                405,
                ErrorCode.METHOD_NOT_ALLOWED,
                "Method not allowed",
                [ErrorDetail(field="method", value=request.method, reason="METHOD_NOT_ALLOWED")],
                headers=dict(exc.headers or {}),
            )
        code = _HTTP_STATUS_CODE.get(exc.status_code)
        if code is None:
            is_server_error = exc.status_code >= 500
            code = ErrorCode.INTERNAL_ERROR if is_server_error else ErrorCode.VALIDATION_FAILED
        return error_response(exc.status_code, code, str(exc.detail))
