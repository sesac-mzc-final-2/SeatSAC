"""오류 형식: contracts/schemas/error.v1.json 그대로 — { code, message, traceId, details[] }.

ops-api 코드 3개(SERVICE_NOT_STOPPABLE·ROLLOUT_IN_PROGRESS·REPORT_UNAVAILABLE)는 이 서비스 안에서만
정의한다(conventions 6장). 나머지는 공통 코드와 같은 이름.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from seatsac_ops.common.trace import current_trace_id


class ErrorCode(StrEnum):
    VALIDATION_FAILED = "VALIDATION_FAILED"
    UNAUTHORIZED = "UNAUTHORIZED"
    FORBIDDEN = "FORBIDDEN"
    NOT_FOUND = "NOT_FOUND"
    METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"
    # ops-api 전용 (ops.openapi.yaml)
    SERVICE_NOT_STOPPABLE = "SERVICE_NOT_STOPPABLE"
    ROLLOUT_IN_PROGRESS = "ROLLOUT_IN_PROGRESS"
    REPORT_UNAVAILABLE = "REPORT_UNAVAILABLE"


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


# --- 자주 쓰는 오류 -----------------------------------------------------------


def unauthorized(reason: str) -> ApiError:
    """reason TOKEN_REQUIRED(토큰 없음) · INVALID_TOKEN(만료·서명·issuer·client_id)."""
    return ApiError(
        401, ErrorCode.UNAUTHORIZED, "Authentication required", [ErrorDetail(reason=reason)]
    )


def forbidden() -> ApiError:
    return ApiError(
        403, ErrorCode.FORBIDDEN, "Admin group required", [ErrorDetail(reason="NOT_ADMIN")]
    )


def validation_failed(field_name: str, reason: str) -> ApiError:
    return ApiError(
        400,
        ErrorCode.VALIDATION_FAILED,
        "Request validation failed",
        [ErrorDetail(field=field_name, reason=reason)],
    )


def not_stoppable(service: str) -> ApiError:
    return ApiError(
        400,
        ErrorCode.SERVICE_NOT_STOPPABLE,
        "Service cannot be stopped or started",
        [ErrorDetail(field="service", value=service[:32])],
    )


def rollout_in_progress(service: str) -> ApiError:
    return ApiError(
        409,
        ErrorCode.ROLLOUT_IN_PROGRESS,
        "Blue/green rollout in progress",
        [ErrorDetail(field="service", value=service, reason="ROLLOUT_IN_PROGRESS")],
    )


def upstream_unavailable(upstream: str, reason: str) -> ApiError:
    """Kubernetes API·ai·coupon·Cognito JWKS를 부르지 못함.

    details[{field: upstream, value, reason}]
    """
    return ApiError(
        503,
        ErrorCode.SERVICE_UNAVAILABLE,
        "Upstream unavailable",
        [ErrorDetail(field="upstream", value=upstream, reason=reason)],
    )


def report_unavailable(reason: str) -> ApiError:
    return ApiError(
        503,
        ErrorCode.REPORT_UNAVAILABLE,
        "Report storage unavailable",
        [ErrorDetail(reason=reason)],
    )


def not_found(field_name: str) -> ApiError:
    return ApiError(404, ErrorCode.NOT_FOUND, "Not found", [ErrorDetail(field=field_name)])


# --- 예외 → 응답 --------------------------------------------------------------

_HTTP_STATUS_CODE = {
    401: ErrorCode.UNAUTHORIZED,
    403: ErrorCode.FORBIDDEN,
    404: ErrorCode.NOT_FOUND,
}


def _validation_details(exc: RequestValidationError) -> list[ErrorDetail]:
    """필드 이름 + 사유만. 사용자 입력값은 담지 않는다(ai와 같은 규칙)."""
    details = []
    for err in exc.errors():
        if err["type"] == "json_invalid":
            return [ErrorDetail(reason="MALFORMED_BODY")]
        path = ".".join(str(p) for p in err["loc"] if p not in ("body", "query", "path", "header"))
        if not path and err["type"] == "missing":
            return [ErrorDetail(reason="MALFORMED_BODY")]
        details.append(ErrorDetail(field=path or None, reason=str(err["type"]).upper()))
    return details


def install_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return error_response(exc.status, exc.code, exc.message, exc.details)

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        return error_response(
            400, ErrorCode.VALIDATION_FAILED, "Request validation failed", _validation_details(exc)
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        if exc.status_code == 405:
            return error_response(
                405,
                ErrorCode.METHOD_NOT_ALLOWED,
                "Method not allowed",
                [ErrorDetail(field="method", value=request.method, reason="METHOD_NOT_ALLOWED")],
                headers=dict(exc.headers or {}),
            )
        code = _HTTP_STATUS_CODE.get(exc.status_code)
        if code is None:
            code = (
                ErrorCode.INTERNAL_ERROR if exc.status_code >= 500 else ErrorCode.VALIDATION_FAILED
            )
        return error_response(exc.status_code, code, str(exc.detail))
