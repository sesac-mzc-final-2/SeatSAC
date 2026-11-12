"""질문 하나를 처리하는 순서 (WBS 3.4.3 · 기능명세서 v4 15-2).

지금 들어 있는 단계: ⑥ Claude 1회 호출(프리셋/SQL/범위 밖) → ⑦ 실행 → ⑨ 답·로그·지표
나중에 끼워 넣을 단계(같은 함수 앞쪽):
  ② 개인 질문("내 예매")  → 3.4.10
  ③④ 정확 일치·의미 캐시 → 3.4.5 (⑧ 캐시 저장도 같이)
① 사용 한도는 이 함수 밖(api/chat.py)에서 QuotaLimiter 로 감싼다.

재요청 규칙: Claude 호출은 질문당 최대 3회(설정 APP_AI_LLM_MAX_CALLS_PER_QUERY).
검증 거부·SQL 오류·프리셋 빈칸 오류가 나면 사유를 붙여 다시 묻는다. 남은 호출이 없으면 REJECTED.
전체 기한 25초(APP_AI_REQUEST_DEADLINE_SECONDS) 안에서만 — 매 단계 남은 시간으로 상한을 줄인다.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import re
import secrets
import time
from collections.abc import Callable
from datetime import datetime
from typing import Any

from seatsac_ai.common.errors import llm_unavailable, snapshot_not_ready
from seatsac_ai.common.metrics import AI_CHAT, AI_TOKENS
from seatsac_ai.config import Settings
from seatsac_ai.llm import prompt
from seatsac_ai.llm.client import LlmClient, LlmUnavailable, ToolCall, Usage
from seatsac_ai.llm.tools import OUT_OF_SCOPE, RUN_PRESET, RUN_SQL, tool_definitions
from seatsac_ai.resources import Preset, presets
from seatsac_ai.snapshot.mapper import KST
from seatsac_ai.snapshot.store import SnapshotNotReady, SnapshotStore
from seatsac_ai.sql.executor import (
    SQL_ERROR,
    TIMEOUT,
    QueryResult,
    SqlExecutionError,
    execute_async,
)
from seatsac_ai.sql.validator import (
    SqlRejected,
    exclude_started,
    sql_template,
    validate,
)

log = logging.getLogger(__name__)

# 같은 파드 안 반복 질문 추적용(되돌릴 수 없음). 파드가 바뀌면 키도 바뀐다 (runtime-contract 9장)
_HASH_KEY = secrets.token_bytes(32)

OUT_OF_SCOPE_ANSWER = "영화·극장·상영 시간에 대한 질문에만 답할 수 있어요."
REJECTED_ANSWER = "그 질문은 지금 데이터로 답하기 어렵습니다. 영화·극장·회차에 대해 물어봐 주세요."
_REJECTION_MESSAGE = {
    "NOT_SELECT": "조회가 아닌 SQL입니다.",
    "MULTI_STATEMENT": "SQL이 여러 문장입니다.",
    "FORBIDDEN_OBJECT": "허용되지 않은 테이블을 조회하는 SQL입니다.",
    "FORBIDDEN_FUNCTION": "허용되지 않은 기능을 쓰는 SQL입니다.",
    "PARSE_ERROR": "SQL을 해석하지 못했습니다.",
    "SQL_ERROR": "SQL 실행에 실패했습니다.",
    "TIMEOUT": "조회가 너무 오래 걸려 중단했습니다.",
}
_SLOT_FORMAT = {
    "time": re.compile(r"^([01]\d|2[0-3]):[0-5]\d$"),
    "date": re.compile(r"^\d{4}-\d{2}-\d{2}$"),
}


class _Retry(Exception):
    """Claude에게 사유를 붙여 다시 묻는다. rejection 은 남은 호출이 없을 때 쓸 거부 사유."""

    def __init__(self, message: str, rejection: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.rejection = rejection


class ChatService:
    def __init__(
        self,
        store: SnapshotStore,
        llm: LlmClient,
        settings: Settings,
        clock: Callable[[], datetime] = lambda: datetime.now(KST),
    ) -> None:
        self._store = store
        self._clock = clock  # 테스트가 "지금"을 고정할 때만 바꾼다
        self._llm = llm
        self._s = settings
        self._presets = {p.preset_id: p for p in presets()}
        self._tools = tool_definitions()

    def ready(self) -> bool:
        """스냅샷 첫 적재가 끝났다. api/chat.py 가 사용 한도를 깎기 전에 먼저 본다."""
        return self._store.is_ready()

    async def answer(self, question: str, now: datetime | None = None) -> dict[str, Any]:
        """계약 ChatResponse 에서 sessionId 를 뺀 나머지를 돌려준다."""
        started = time.monotonic()
        deadline = started + self._s.request_deadline_seconds
        if not self._store.is_ready():
            raise snapshot_not_ready()
        now = (now or self._clock()).astimezone(KST)
        system_params = {"today": f"{now:%Y-%m-%d}", "now": f"{now:%Y-%m-%d %H:%M}"}

        usage = Usage()
        calls = 0
        system = prompt.system_blocks(self._s.prompt_cache_enabled)
        messages: list[dict[str, Any]] = [
            {"role": "user", "content": prompt.user_text(question, now)}
        ]
        last_rejection: dict[str, Any] | None = None
        last_sql: str | None = None
        body: dict[str, Any] | None = None
        # Claude 실패 지표의 path: 직전에 고른 도구를 따른다.
        # 첫 호출(도구를 고르기 전) 실패는 nl2sql로 센다 — 경로를 정하기 전이라 나눌 수 없다.
        failed_path = "nl2sql"

        try:
            while calls < self._s.llm_max_calls_per_query:
                timeout = min(self._s.llm_timeout_seconds, deadline - time.monotonic())
                if timeout <= 0:
                    raise LlmUnavailable("TIMEOUT")
                call = await self._llm.call(system, self._tools, messages, timeout)
                calls += 1
                usage.add(call.usage)
                failed_path = "preset" if call.name == RUN_PRESET else "nl2sql"
                try:
                    body = await self._run_tool(call, system_params, deadline)
                    break
                except _Retry as retry:
                    last_rejection = retry.rejection or last_rejection
                    if call.name == RUN_SQL:
                        last_sql = str(call.input.get("sql", ""))
                    if (retry.rejection or {}).get("final"):
                        break
                    messages += _feedback(call, retry.message)
        except LlmUnavailable as exc:
            AI_CHAT.labels(failed_path, "error").inc()
            raise llm_unavailable(exc.reason) from exc  # type: ignore[arg-type]
        except SnapshotNotReady as exc:
            # 질의 도중 스냅샷이 아직 준비 안 됨(기동 직후 교체 순간). 계약의 503으로
            raise snapshot_not_ready() from exc

        if body is None:  # 재요청을 다 쓰고도 실패
            last = last_rejection or {}
            body = _rejected(
                last.get("reason", "PARSE_ERROR"),
                last.get("object"),
                last_sql,
                self._as_of(),
                message=last.get("message"),
            )

        body["llm"] = {
            "calls": calls,
            "inputTokens": usage.input,
            "outputTokens": usage.output,
            "cacheReadTokens": usage.cache_read,
            "cacheWriteTokens": usage.cache_write,
        }
        body["latencyMs"] = int((time.monotonic() - started) * 1000)
        self._record(question, body, usage)
        return body

    # --- 도구별 처리 ---------------------------------------------------------

    async def _run_tool(self, call: ToolCall, system_params, deadline) -> dict[str, Any]:
        if call.name == OUT_OF_SCOPE:
            return _empty("OUT_OF_SCOPE", OUT_OF_SCOPE_ANSWER, self._as_of())
        if call.name == RUN_PRESET:
            preset, slots = self._resolve_preset(call.input)
            _, result = await self._execute(preset.sql, system_params | slots, deadline)
            return self._result_body("PRESET", _answer(preset.title, result), result, None, preset)
        if call.name == RUN_SQL:
            sql = str(call.input.get("sql", ""))
            # maxLength 80은 도구 스키마의 힌트일 뿐 API가 강제하지 않는다
            explanation = str(call.input.get("explanation") or "조회 결과").strip()[:80]
            ran_sql, result = await self._execute(sql, system_params, deadline, claude_sql=True)
            return self._result_body("NL2SQL", _answer(explanation, result), result, ran_sql, None)
        raise _Retry(f"알 수 없는 도구 {call.name}")

    async def _execute(
        self, sql: str, params: dict[str, Any], deadline: float, claude_sql: bool = False
    ) -> tuple[str, QueryResult]:
        """검증 → 실행. (실제로 실행한 SQL, 결과)

        Claude가 쓴 SQL(claude_sql)은 screening 을 읽는 SELECT마다 `starts_at > :now` 를
        붙여 실행한다(data-model 6-1, #93 리뷰). 조건을 빼먹거나 지금보다 이른 시각과 비교해도
        시작한 회차가 나오지 않는다. 프리셋은 SQL에 :now 가 이미 들어 있다.
        """
        try:
            validated = validate(sql, self._s.max_rows)
        except SqlRejected as r:
            rejection = {"reason": r.reason, "object": r.object}
            raise _Retry(f"SQL이 거부되었다({r.reason}): {r.message}", rejection) from r
        if claude_sql:
            validated = exclude_started(validated)
        timeout = min(self._s.sql_timeout_seconds, deadline - time.monotonic())
        if timeout <= 0:
            raise LlmUnavailable("TIMEOUT")
        try:
            result = await execute_async(
                self._store, validated, params, timeout_seconds=timeout, max_rows=self._s.max_rows
            )
            return validated.sql, result
        except SqlExecutionError as e:
            if e.reason == TIMEOUT:
                # 다시 물어도 같은 무거운 SQL일 가능성이 커서 바로 거부한다
                raise _Retry(e.message, {"reason": TIMEOUT, "final": True}) from e
            raise _Retry(f"SQL 실행 오류: {e.message}", {"reason": SQL_ERROR}) from e

    def _resolve_preset(self, raw: dict[str, Any]) -> tuple[Preset, dict[str, Any]]:
        preset = self._presets.get(str(raw.get("presetId")))
        if preset is None:
            raise _Retry(f"없는 presetId: {raw.get('presetId')}. 목록의 presetId만 쓴다")
        given = raw.get("params") or {}
        slots: dict[str, Any] = {}
        for name in preset.params:
            value = given.get(name)
            if value in (None, ""):
                raise _Retry(f"{preset.preset_id}에는 빈칸 {name}이 필요하다")
            if name in _SLOT_FORMAT and not _SLOT_FORMAT[name].fullmatch(str(value)):
                raise _Retry(f"빈칸 {name}의 형식이 틀렸다: {value}")
            if name == "n":
                try:
                    value = max(1, min(50, int(value)))
                except (TypeError, ValueError):
                    raise _Retry(f"빈칸 n은 정수여야 한다: {value}") from None
            if name in ("theater", "movie"):
                value = self._known_name(name, str(value))
            slots[name] = value
        return preset, slots

    def _known_name(self, kind: str, value: str) -> str:
        table, column = ("theater", "name") if kind == "theater" else ("movie", "title")
        with self._store.reader() as conn:
            names = [r[0] for r in conn.execute(f"SELECT {column} FROM {table}")]  # noqa: S608
        if value in names:
            return value
        close = [n for n in names if value in n or n in value]
        if len(close) == 1:
            return close[0]  # '건대' → '건대입구'
        raise _Retry(f"{kind} '{value}'이 스냅샷에 없다. 가능한 값: {', '.join(names[:30])}")

    # --- 응답·기록 -----------------------------------------------------------

    def _as_of(self) -> str:
        as_of = self._store.as_of() or datetime.now(KST)
        return as_of.astimezone(KST).isoformat(timespec="seconds")

    def _result_body(self, path, answer, result, sql, preset) -> dict[str, Any]:
        as_of = self._as_of()
        return {
            "answer": f"{answer} ({_clock(as_of)} 기준)",
            "path": path,
            "columns": result.columns,
            "rows": result.rows,
            "rowCount": result.row_count,
            "truncated": result.truncated,
            "sql": sql,
            "presetId": preset.preset_id if preset else None,
            "asOf": as_of,
            "rejection": None,
        }

    def _record(self, question: str, body: dict[str, Any], usage: Usage) -> None:
        path = body["path"].lower()
        AI_CHAT.labels(path, "ok").inc()
        for kind, n in (
            ("input", usage.input),
            ("output", usage.output),
            ("cache_read", usage.cache_read),
            ("cache_write", usage.cache_write),
        ):
            if n:
                AI_TOKENS.labels(kind, path).inc(n)
        digest = hmac.new(_HASH_KEY, question.encode(), hashlib.sha256).hexdigest()[:16]
        log.info(
            "chat answered",
            extra={
                "fields": {
                    "path": body["path"],
                    "presetId": body["presetId"],
                    "latencyMs": body["latencyMs"],
                    "llmCalls": body["llm"]["calls"],
                    "tokens": {
                        "input": usage.input,
                        "output": usage.output,
                        "cacheRead": usage.cache_read,
                    },
                    "questionLength": len(question),
                    "questionHash": digest,
                    "sqlTemplate": sql_template(body["sql"]) if body["sql"] else None,
                    "rowCount": body["rowCount"],
                    "rejection": (body["rejection"] or {}).get("reason"),
                }
            },
        )


def _feedback(call: ToolCall, message: str) -> list[dict[str, Any]]:
    return [
        {"role": "assistant", "content": call.content},
        {
            "role": "user",
            "content": [
                {
                    "type": "tool_result",
                    "tool_use_id": call.tool_use_id,
                    "is_error": True,
                    "content": f"{message}. 고쳐서 다시 도구를 호출하라.",
                }
            ],
        },
    ]


def _answer(subject: str, result: QueryResult) -> str:
    if result.row_count == 0:
        return f"{subject}: 조건에 맞는 결과가 없습니다."
    more = " (500건까지 표시)" if result.truncated else ""
    return f"{subject}: {result.row_count}건입니다.{more}"


def _clock(iso: str) -> str:
    t = datetime.fromisoformat(iso)
    return f"{t.hour}시 {t.minute}분"


def _empty(path: str, answer: str, as_of: str) -> dict[str, Any]:
    return {
        "answer": answer,
        "path": path,
        "columns": None,
        "rows": None,
        "rowCount": 0,
        "truncated": False,
        "sql": None,
        "presetId": None,
        "asOf": as_of,
        "rejection": None,
    }


def _rejected(
    reason: str, obj: str | None, sql: str | None, as_of: str, *, message: str | None = None
) -> dict[str, Any]:
    body = _empty("REJECTED", REJECTED_ANSWER, as_of)
    body["sql"] = sql
    body["rejection"] = {
        "reason": reason,
        "message": message or _REJECTION_MESSAGE.get(reason, "SQL을 실행할 수 없습니다."),
        "object": obj,
    }
    return body
