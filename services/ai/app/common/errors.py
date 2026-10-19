"""오류 응답 — contracts/schemas/error.v1.json 과 같은 형식. Java ErrorCode 와 코드 이름을 맞춘다."""
from fastapi import Request

STATUS = {
    "VALIDATION_FAILED": 400, "UNAUTHORIZED": 401, "FORBIDDEN": 403, "NOT_FOUND": 404,
    "RATE_LIMITED": 429, "INTERNAL_ERROR": 500, "SERVICE_UNAVAILABLE": 503,
}


class BusinessError(Exception):
    def __init__(self, code: str, message: str, details=None):
        self.code, self.message, self.details = code, message, details
        self.status = STATUS.get(code, 500)


def error_body(code: str, message: str, request: Request, details=None) -> dict:
    body = {"code": code, "message": message, "traceId": getattr(request.state, "trace_id", None)}
    if details is not None:
        body["details"] = details
    return body
