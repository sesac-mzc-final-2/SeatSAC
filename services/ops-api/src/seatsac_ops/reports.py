"""OPS-22 일자별 오류 리포트 — S3 파티션 목록 · Athena 쿼리 · presigned URL 5분 (ADR-121,
ops.openapi.yaml).

- 원본: 리포트 버킷 `errors/dt=YYYY-MM-DD/`(6.1.9 내보내기 CronJob) → Glue 테이블(6.1.8) → Athena
  작업 그룹
- 쿼리는 정해 둔 SQL 하나. 날짜는 형식을 검사한 뒤 실행 매개변수(ExecutionParameters)로만 넣는다
- 결과 CSV 열: timestamp, service, level, errorCategory, status, path, traceId, message
  (IP·토큰·질문 원문 없음)
- 같은 날짜 결과가 1시간 안이면 쿼리를 다시 돌리지 않는다(프로세스 메모리 — 워커 1)
- presigned URL은 로그·감사 로그에 남기지 않는다
- 버킷·작업 그룹 설정이 없거나(6.1.8·6.1.9 전) AWS 오류면 503 REPORT_UNAVAILABLE
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any
from zoneinfo import ZoneInfo

from botocore.exceptions import BotoCoreError, ClientError
from starlette.concurrency import run_in_threadpool

from seatsac_ops.common.errors import ApiError, not_found, report_unavailable, validation_failed
from seatsac_ops.config import Settings

log = logging.getLogger(__name__)
KST = ZoneInfo("Asia/Seoul")

DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")
QUERY_ID_PATTERN = re.compile(r"^[A-Za-z0-9-]{1,128}$")
PARTITION = re.compile(r"^errors/dt=(\d{4}-\d{2}-\d{2})/$")
PREFIX = "errors/"


def report_sql(database: str, table: str) -> str:
    # database·table은 설정 검증(소문자·숫자·밑줄)을 거친 값, 날짜는 ? 자리(ExecutionParameters)
    return (
        'SELECT "timestamp", service, level, errorcategory AS "errorCategory", status, path, '
        f'traceid AS "traceId", message FROM "{database}"."{table}" '
        'WHERE dt = ? ORDER BY "timestamp"'
    )


@dataclass
class _Done:
    query_id: str
    output: str
    row_count: int
    generated_at: datetime
    finished_monotonic: float


class ReportService:
    def __init__(
        self, settings: Settings, s3: Any | None = None, athena: Any | None = None
    ) -> None:
        self._settings = settings
        self._s3 = s3
        self._athena = athena
        self._by_date: dict[str, _Done] = {}
        self._date_of_query: dict[str, str] = {}

    # --- AWS 클라이언트 (Pod Identity 자격 증명, 처음 쓸 때 만든다) -----------
    def _clients(self) -> tuple[Any, Any]:
        if not self._settings.reports_bucket or not self._settings.athena_workgroup:
            raise report_unavailable("NOT_CONFIGURED")
        if self._s3 is None or self._athena is None:
            import boto3  # 기동을 가볍게 — 리포트를 쓸 때만 불러온다

            region = self._settings.aws_region
            self._s3 = self._s3 or boto3.client("s3", region_name=region)
            self._athena = self._athena or boto3.client("athena", region_name=region)
        return self._s3, self._athena

    async def _call(self, fn: Any, **kwargs: Any) -> Any:
        try:
            return await run_in_threadpool(fn, **kwargs)
        except ApiError:
            raise
        except (ClientError, BotoCoreError) as exc:
            log.warning("리포트 AWS 호출 실패: %s", _aws_code(exc))
            raise report_unavailable(_aws_code(exc)) from exc

    # --- GET /ops/reports/errors/dates -------------------------------------
    async def dates(self) -> list[str]:
        s3, _ = self._clients()
        found: set[str] = set()
        token: str | None = None
        while True:
            kwargs: dict[str, Any] = {
                "Bucket": self._settings.reports_bucket,
                "Prefix": PREFIX,
                "Delimiter": "/",
            }
            if token:
                kwargs["ContinuationToken"] = token
            page = await self._call(s3.list_objects_v2, **kwargs)
            for prefix in page.get("CommonPrefixes") or []:
                if match := PARTITION.match(prefix.get("Prefix", "")):
                    found.add(match.group(1))
            if not page.get("IsTruncated"):
                break
            token = page.get("NextContinuationToken")
        return sorted(found, reverse=True)

    # --- GET /ops/reports/errors?date= -------------------------------------
    async def request(self, day: str) -> tuple[int, dict[str, Any]]:
        if not DATE_PATTERN.fullmatch(day or ""):
            raise validation_failed("date", "FORMAT")
        try:
            date.fromisoformat(day)
        except ValueError as exc:
            raise validation_failed("date", "FORMAT") from exc
        s3, athena = self._clients()
        cached = self._by_date.get(day)
        if (
            cached
            and time.monotonic() - cached.finished_monotonic < self._settings.report_cache_seconds
        ):
            return 200, {
                "downloadUrl": await self._presign(s3, cached.output),
                "rowCount": cached.row_count,
                "generatedAt": cached.generated_at.isoformat(timespec="seconds"),
            }
        started = await self._call(
            athena.start_query_execution,
            QueryString=report_sql(self._settings.report_database, self._settings.report_table),
            QueryExecutionContext={"Database": self._settings.report_database},
            WorkGroup=self._settings.athena_workgroup,
            ExecutionParameters=[f"'{day}'"],  # 형식 검사를 통과한 YYYY-MM-DD만
        )
        query_id = started["QueryExecutionId"]
        self._date_of_query[query_id] = day
        return 202, {"queryId": query_id, "state": "RUNNING"}

    # --- GET /ops/reports/errors/{queryId} ---------------------------------
    async def status(self, query_id: str) -> dict[str, Any]:
        if not QUERY_ID_PATTERN.fullmatch(query_id or ""):
            raise not_found("queryId")
        s3, athena = self._clients()
        try:
            execution = (
                await run_in_threadpool(athena.get_query_execution, QueryExecutionId=query_id)
            )["QueryExecution"]
        except ClientError as exc:
            # 없는 ID는 InvalidRequest, 다른 작업 그룹의 ID는 IAM이 막아 AccessDenied
            # — 둘 다 보여 주지 않는다(404)
            if _aws_code(exc) in ("InvalidRequestException", "AccessDeniedException"):
                raise not_found("queryId") from exc
            raise report_unavailable(_aws_code(exc)) from exc
        except BotoCoreError as exc:
            raise report_unavailable(_aws_code(exc)) from exc
        if execution.get("WorkGroup") != self._settings.athena_workgroup:
            raise not_found("queryId")  # 다른 작업 그룹의 쿼리는 보여 주지 않는다

        state = (execution.get("Status") or {}).get("State")
        if state in ("QUEUED", "RUNNING"):
            return {"state": "RUNNING"}
        if state != "SUCCEEDED":
            return {"state": "FAILED"}

        output = (execution.get("ResultConfiguration") or {}).get("OutputLocation") or ""
        stats = await self._call(athena.get_query_runtime_statistics, QueryExecutionId=query_id)
        row_count = int(
            ((stats.get("QueryRuntimeStatistics") or {}).get("Rows") or {}).get("OutputRows", 0)
        )
        completed = (execution.get("Status") or {}).get("CompletionDateTime")
        generated = (
            completed if isinstance(completed, datetime) else datetime.now(KST)
        ).astimezone(KST)
        if (day := self._date_of_query.get(query_id)) is not None:
            self._by_date[day] = _Done(query_id, output, row_count, generated, time.monotonic())
        return {
            "state": "SUCCEEDED",
            "downloadUrl": await self._presign(s3, output),
            "rowCount": row_count,
            "generatedAt": generated.isoformat(timespec="seconds"),
        }

    async def _presign(self, s3: Any, output: str) -> str:
        match = re.match(r"^s3://([^/]+)/(.+)$", output)
        if not match:
            raise report_unavailable("NO_OUTPUT")
        return await self._call(
            s3.generate_presigned_url,
            ClientMethod="get_object",
            Params={"Bucket": match.group(1), "Key": match.group(2)},
            ExpiresIn=self._settings.report_url_seconds,
        )


def _aws_code(exc: Exception) -> str:
    if isinstance(exc, ClientError):
        return str(exc.response.get("Error", {}).get("Code") or "CLIENT_ERROR")
    return type(exc).__name__
