"""로그 형식. Java `libs/common-web/logback-common.xml`과 같은 규칙.

- APP_ENV=local: 사람이 읽는 한 줄 (`HH:mm:ss.SSS LEVEL [traceId] logger - message`)
- 그 밖(staging): stdout JSON 한 줄
  timestamp(+09:00)·level·service=ai·logger·traceId·userId(있으면)·message·error(있으면)
  + 요청별 필드(path·latencyMs·tokens 등, service-runtime-contract 9장).
질문·SQL·결과 행 수만 남기고 결과 행 내용·토큰 값·비밀번호는 남기지 않는다.
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

from seatsac_ai.common.trace import trace_id_var, user_id_var

KST = ZoneInfo("Asia/Seoul")
SERVICE_NAME = "ai"


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        timestamp = datetime.fromtimestamp(record.created, KST).isoformat(timespec="milliseconds")
        payload: dict[str, object] = {
            "timestamp": timestamp,
            "level": record.levelname,
            "service": SERVICE_NAME,
            "logger": record.name,
        }
        if (trace_id := trace_id_var.get()) is not None:
            payload["traceId"] = trace_id
        if (user_id := user_id_var.get()) is not None:
            payload["userId"] = user_id
        # logger.info("...", extra={"fields": {"path": "preset", "latencyMs": 38}})
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
    # uvicorn의 기동·종료 로그도 같은 형식으로
    for name in ("uvicorn", "uvicorn.error"):
        logger = logging.getLogger(name)
        logger.handlers = []
        logger.propagate = True
    # 접근 로그는 RequestContextMiddleware가 traceId와 함께 남기므로 uvicorn 것은 끈다
    # (--no-access-log를 줘도 propagate를 켜면 다시 살아나므로 명시적으로 막는다)
    logging.getLogger("uvicorn.access").disabled = True
