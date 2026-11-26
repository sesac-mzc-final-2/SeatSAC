"""로그 형식 — ai `common/logging.py`와 같은 규칙(service만 ops-api).

staging: stdout JSON 한 줄(timestamp +09:00·level·service·logger·traceId·userId·message·error +
필드).
토큰·presigned URL·Secret 값은 남기지 않는다.
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

from seatsac_ops.common.trace import trace_id_var, user_id_var

KST = ZoneInfo("Asia/Seoul")
SERVICE_NAME = "ops-api"


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "timestamp": datetime.fromtimestamp(record.created, KST).isoformat(
                timespec="milliseconds"
            ),
            "level": record.levelname,
            "service": SERVICE_NAME,
            "logger": record.name,
        }
        if (trace_id := trace_id_var.get()) is not None:
            payload["traceId"] = trace_id
        if (user_id := user_id_var.get()) is not None:
            payload["userId"] = user_id
        payload.update(getattr(record, "fields", {}))
        payload["message"] = record.getMessage()
        if record.exc_info:
            payload["error"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, default=str)


class PlainFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        clock = datetime.fromtimestamp(record.created, KST).strftime("%H:%M:%S.%f")[:-3]
        line = (
            f"{clock} {record.levelname:<5} [{trace_id_var.get() or ''}] "
            f"{record.name} - {record.getMessage()}"
        )
        if fields := getattr(record, "fields", None):
            line += " " + " ".join(f"{k}={v}" for k, v in fields.items())
        if record.exc_info:
            line += "\n" + self.formatException(record.exc_info)
        return line


def configure_logging(app_env: str = "staging", level: int = logging.INFO) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(PlainFormatter() if app_env == "local" else JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level)
    for name in ("uvicorn", "uvicorn.error"):
        logger = logging.getLogger(name)
        logger.handlers = []
        logger.propagate = True
    logging.getLogger("uvicorn.access").disabled = True
    # boto·httpx의 요청 로그(주소·서명 쿼리)가 섞이지 않게
    for name in ("botocore", "boto3", "urllib3", "httpx", "httpcore"):
        logging.getLogger(name).setLevel(logging.WARNING)
