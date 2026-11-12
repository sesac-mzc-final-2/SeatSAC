"""질문 하나를 처리하는 순서 (WBS 3.4.3 · 기능명세서 v4 15-2).

  ③ 슬롯 추출 → ④ 정확 일치 캐시 → ⑤ 의미 캐시 → (못 찾으면) ⑥ Claude(프리셋/SQL/범위 밖)
  → ⑦ 실행 → ⑧ 성공한 PRESET·NL2SQL 캐시 저장 → ⑨ 답·로그·지표·세션
  ② 개인 질문("내 예매")은 캐시·Claude보다 먼저
     — 공개 API를 사용자 토큰으로 불러 템플릿 문장(bookings.py)
① 사용 한도는 이 함수 밖(api/chat.py)에서 QuotaLimiter 로 감싼다.
캐시(ai Valkey)가 없으면(local) ③~⑤·⑧·세션을 건너뛰고 늘 Claude로 답한다.

후속 질문("그중 건대입구만")은 직전 질문·SQL(세션)을 Claude에게 같이 주고,
캐시를 보지도 저장하지도 않는다
— 같은 글자라도 직전 질문에 따라 뜻이 달라서.

재요청 규칙: Claude 호출은 질문당 최대 3회(설정 APP_AI_LLM_MAX_CALLS_PER_QUERY).
검증 거부·SQL 오류·프리셋 빈칸 오류가 나면 사유를 붙여 다시 묻는다. 남은 호출이 없으면 REJECTED.
전체 기한 25초(APP_AI_REQUEST_DEADLINE_SECONDS) 안에서만 — 매 단계 남은 시간으로 상한을 줄인다.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import logging
import re
import secrets
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from seatsac_ai import bookings as my_bookings
from seatsac_ai.cache import template as tpl
from seatsac_ai.cache.slots import Extracted, extract, is_followup, meaning_signature
from seatsac_ai.cache.store import CacheEntry, ChatCache
from seatsac_ai.common.errors import ApiError, llm_unavailable, snapshot_not_ready
from seatsac_ai.common.metrics import AI_CACHE_REQUESTS, AI_CHAT, AI_TOKENS
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


@dataclass(frozen=True)
class _Made:
    """Claude 경로가 실제로 실행한 것. ⑧ 캐시 저장에 쓴다."""

    origin: str  # PRESET · NL2SQL
    sql: str  # NL2SQL: Claude가 쓴 SQL(LIMIT 붙이기 전) · PRESET: 프리셋 SQL
    preset: Preset | None
    params: dict[str, Any]  # PRESET 빈칸 값
    subject: str  # 답 문장 앞부분
    result: QueryResult


class ChatService:
    def __init__(
        self,
        store: SnapshotStore,
        llm: LlmClient,
        settings: Settings,
        clock: Callable[[], datetime] = lambda: datetime.now(KST),
        cache: ChatCache | None = None,
        bookings: my_bookings.MyBookingsClient | None = None,
    ) -> None:
        self._store = store
        self._cache = cache
        self._bookings = bookings
        self._clock = clock  # 테스트가 "지금"을 고정할 때만 바꾼다
        self._llm = llm
        self._s = settings
        self._presets = {p.preset_id: p for p in presets()}
        self._tools = tool_definitions()

    def ready(self) -> bool:
        """스냅샷 첫 적재가 끝났다. api/chat.py 가 사용 한도를 깎기 전에 먼저 본다."""
        return self._store.is_ready()

    async def answer(
        self,
        question: str,
        now: datetime | None = None,
        *,
        user_id: str | None = None,
        session_id: str | None = None,
        authorization: str | None = None,
    ) -> dict[str, Any]:
        """계약 ChatResponse. 캐시가 있으면 sessionId 도 채운다(없으면 api/chat.py 가 새로 발급)."""
        started = time.monotonic()
        deadline = started + self._s.request_deadline_seconds
        if not self._store.is_ready():
            raise snapshot_not_ready()
        now = (now or self._clock()).astimezone(KST)
        system_params = tpl.system_params(now.date(), f"{now:%Y-%m-%d %H:%M}")

        cache = self._cache
        sid, previous = session_id, None
        if cache is not None and user_id is not None:
            sid, previous = await cache.load_session(user_id, session_id)
        follow_up = previous is not None and is_followup(question)
        # 문맥(세션)이 없어도 "그중 …" 말투는 캐시를 보지도 저장하지도 않는다(#94 리뷰)
        skip_cache = is_followup(question)

        usage = Usage()
        calls = 0
        body: dict[str, Any] | None = None
        ext: Extracted | None = None
        vector: list[float] | None = None
        try:
            # ② 개인 질문 — Claude·캐시 없이
            if self._bookings is not None and my_bookings.is_personal(question):
                body = await self._my_bookings(authorization, now)
            # ③④⑤ 캐시
            elif cache is not None and not skip_cache:
                ext = self._extract(question, now)
                if ext.cacheable:
                    body, vector = await self._from_cache(cache, ext, system_params, deadline)
            # ⑥⑦ Claude
            if body is None:
                body, made, calls = await self._ask_claude(
                    question, now, system_params, deadline, usage, previous if follow_up else None
                )
                # ⑧ 저장 (후속 질문은 직전 문맥에 기대므로 저장하지 않는다)
                if made is not None and cache is not None and ext is not None and ext.cacheable:
                    await self._save(cache, ext, made, system_params, deadline, vector)
        except SnapshotNotReady as exc:
            # 질의 도중 스냅샷이 아직 준비 안 됨(기동 직후 교체 순간). 계약의 503으로
            raise snapshot_not_ready() from exc

        body["llm"] = {
            "calls": calls,
            "inputTokens": usage.input,
            "outputTokens": usage.output,
            "cacheReadTokens": usage.cache_read,
            "cacheWriteTokens": usage.cache_write,
        }
        body["latencyMs"] = int((time.monotonic() - started) * 1000)
        self._record(question, body, usage)
        if cache is not None and user_id is not None and sid is not None:
            # MY_BOOKINGS도 저장하지 않는다 — SQL 없는 문맥이라 후속 질문에 못 쓴다(#96 리뷰)
            if body["path"] not in ("REJECTED", "OUT_OF_SCOPE", "MY_BOOKINGS"):
                await cache.save_session(
                    user_id, sid, {"question": question, "sql": body["sql"], "path": body["path"]}
                )
            body["sessionId"] = sid
        return body

    async def _ask_claude(
        self, question, now, system_params, deadline, usage, previous
    ) -> tuple[dict[str, Any], _Made | None, int]:
        calls = 0
        system = prompt.system_blocks(self._s.prompt_cache_enabled)
        messages: list[dict[str, Any]] = [
            {"role": "user", "content": prompt.user_text(question, now, previous)}
        ]
        last_rejection: dict[str, Any] | None = None
        last_sql: str | None = None
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
                    body, made = await self._run_tool(call, system_params, deadline)
                    return body, made, calls
                except _Retry as retry:
                    last_rejection = retry.rejection or last_rejection
                    if call.name == RUN_SQL:
                        last_sql = str(call.input.get("sql", ""))
                    if (retry.rejection or {}).get("final"):
                        break
                    messages += _feedback(call, retry.message)
        except LlmUnavailable as exc:
            AI_CHAT.labels(failed_path, "error").inc()
            # 앞선 호출이 성공했다면 그 토큰은 이미 비용이 나갔다 — 실패한 질문이라도 센다
            _count_tokens(usage, failed_path)
            raise llm_unavailable(exc.reason) from exc  # type: ignore[arg-type]
        # 재요청을 다 쓰고도 실패
        last = last_rejection or {}
        rejected = _rejected(
            last.get("reason", "PARSE_ERROR"),
            last.get("object"),
            last_sql,
            self._as_of(),
            message=last.get("message"),
        )
        return rejected, None, calls

    # --- 내 예매 --------------------------------------------------------------

    async def _my_bookings(self, authorization: str | None, now: datetime) -> dict[str, Any]:
        try:
            page = await self._bookings.fetch(authorization)  # type: ignore[union-attr]
        except ApiError as exc:
            # 공개 API 장애(503)만 오류로 센다. 토큰 없음·만료(401)는 사용자 쪽 문제(#96 리뷰)
            if exc.status >= 500:
                AI_CHAT.labels("my_bookings", "error").inc()
            raise
        rows = my_bookings.to_rows(page.items)
        more = f" 최근 {len(rows)}건만 확인했습니다(전체 {page.total}건)." if page.truncated else ""
        as_of = now.isoformat(timespec="seconds")
        return {
            "answer": f"{my_bookings.answer_text(rows, now)}{more} ({_clock(as_of)} 기준)",
            "path": "MY_BOOKINGS",
            "columns": my_bookings.COLUMNS,
            "rows": rows,
            "rowCount": len(rows),
            "truncated": page.truncated,
            "sql": None,
            "presetId": None,
            "asOf": as_of,
            "rejection": None,
        }

    # --- 캐시 -----------------------------------------------------------------

    def _extract(self, question: str, now: datetime) -> Extracted:
        with self._store.reader() as conn:
            theaters = [r[0] for r in conn.execute("SELECT name FROM theater")]
            movies = [r[0] for r in conn.execute("SELECT title FROM movie")]
        return extract(question, now, theaters, movies)

    async def _embed(self, cache: ChatCache, text: str) -> list[float] | None:
        if not (self._s.semantic_cache_enabled and cache.embedder):
            return None
        if not await cache.semantic_available():  # 기동 때 연결 오류였으면 여기서 다시 시도
            return None
        try:
            return await asyncio.to_thread(cache.embedder.embed, text)
        except Exception as exc:  # 모델 파일 문제 등 — 의미 캐시만 건너뛴다
            log.warning("임베딩 실패(의미 캐시 건너뜀): %s", exc)
            return None

    async def _from_cache(
        self, cache: ChatCache, ext: Extracted, system_params, deadline
    ) -> tuple[dict[str, Any] | None, list[float] | None]:
        entry = await cache.get_exact(ext.key_text)
        if entry is not None and entry.kinds != ext.kinds:
            entry = None  # 키는 같은데 슬롯 종류가 다르면 쓰지 않는다(hit로 세지 않음)
        AI_CACHE_REQUESTS.labels("exact", "hit" if entry else "miss").inc()
        path = "CACHE_EXACT"
        vector = None
        if entry is None:
            vector = await self._embed(cache, ext.template)
            if vector is not None:
                found = await cache.nearest(vector, ext.kinds)
                if found is not None and _semantic_match(found[0], found[1], ext, cache.threshold):
                    entry = found[0]
                AI_CACHE_REQUESTS.labels("semantic", "hit" if entry else "miss").inc()
                path = "CACHE_SEMANTIC"
        if entry is None:
            return None, vector
        params = system_params | ext.values()
        try:
            # Claude가 쓴 SQL에서 온 템플릿은 처음 답할 때처럼 시작한 회차 제외 조건을 붙인다
            ran_sql, result = await self._execute(
                entry.sql, params, deadline, claude_sql=entry.origin == "NL2SQL"
            )
        except _Retry as retry:  # 스냅샷 모양이 바뀌었거나 너무 오래 걸림 — Claude로
            log.warning("캐시 SQL 실행 실패(Claude로 처리): %s", retry.message)
            return None, vector
        subject = _fill_subject(entry.subject, ext)
        body = self._result_body(
            path, _answer(subject, result), result, tpl.render(ran_sql, params), entry.preset_id
        )
        return body, vector

    async def _save(self, cache, ext: Extracted, made: _Made, system_params, deadline, vector):
        today = datetime.fromisoformat(system_params["today"]).date()
        try:
            if made.preset is not None:
                template = tpl.preset_template(made.preset.sql, made.params, ext, today)
            else:
                template = tpl.make_template(made.sql, ext, today, system_params["now"])
        except tpl.TemplateError as exc:
            log.info("캐시하지 않음(템플릿 불가): %s", exc)
            return
        # 같은 값으로 템플릿을 다시 실행해 결과가 같을 때만 저장한다(템플릿 실수 = 오적중을 막는다)
        try:
            _, again = await self._execute(
                template.sql,
                system_params | ext.values(),
                deadline,
                claude_sql=made.origin == "NL2SQL",
            )
        except (_Retry, LlmUnavailable) as exc:
            log.info("캐시하지 않음(템플릿 실행 실패): %s", exc)
            return
        if again.rows != made.result.rows:
            log.info("캐시하지 않음(템플릿 결과가 원래 결과와 다름)")
            return
        entry = CacheEntry(
            origin=made.origin,
            preset_id=made.preset.preset_id if made.preset else None,
            sql=template.sql,
            subject=_subject_template(made.subject, ext),
            kinds=ext.kinds,
            relative_words=tuple(sorted(ext.relative_words)),
            anchors=template.anchors,
            template=ext.template,
            meaning=meaning_signature(ext.question),
        )
        if vector is None:
            vector = await self._embed(cache, ext.template)
        await cache.put(ext.key_text, entry, vector)

    # --- 도구별 처리 ---------------------------------------------------------

    async def _run_tool(
        self, call: ToolCall, system_params, deadline
    ) -> tuple[dict[str, Any], _Made | None]:
        if call.name == OUT_OF_SCOPE:
            return _empty("OUT_OF_SCOPE", OUT_OF_SCOPE_ANSWER, self._as_of()), None
        if call.name == RUN_PRESET:
            preset, slots = self._resolve_preset(call.input)
            _, result = await self._execute(preset.sql, system_params | slots, deadline)
            body = self._result_body(
                "PRESET", _answer(preset.title, result), result, None, preset.preset_id
            )
            return body, _Made("PRESET", preset.sql, preset, slots, preset.title, result)
        if call.name == RUN_SQL:
            sql = str(call.input.get("sql", ""))
            # maxLength 80은 도구 스키마의 힌트일 뿐 API가 강제하지 않는다
            explanation = str(call.input.get("explanation") or "조회 결과").strip()[:80]
            ran_sql, result = await self._execute(sql, system_params, deadline, claude_sql=True)
            body = self._result_body("NL2SQL", _answer(explanation, result), result, ran_sql, None)
            return body, _Made("NL2SQL", sql, None, {}, explanation, result)
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

    def _result_body(self, path, answer, result, sql, preset_id) -> dict[str, Any]:
        as_of = self._as_of()
        return {
            "answer": f"{answer} ({_clock(as_of)} 기준)",
            "path": path,
            "columns": result.columns,
            "rows": result.rows,
            "rowCount": result.row_count,
            "truncated": result.truncated,
            "sql": sql,
            "presetId": preset_id,
            "asOf": as_of,
            "rejection": None,
        }

    def _record(self, question: str, body: dict[str, Any], usage: Usage) -> None:
        path = body["path"].lower()
        AI_CHAT.labels(path, "ok").inc()
        _count_tokens(usage, path)
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


def _count_tokens(usage: Usage, path: str) -> None:
    """ai_tokens_total. input·output = 우리 캐시가 줄이는 비용, cache_* = 프롬프트 캐싱(따로)."""
    for kind, n in (
        ("input", usage.input),
        ("output", usage.output),
        ("cache_read", usage.cache_read),
        ("cache_write", usage.cache_write),
    ):
        if n:
            AI_TOKENS.labels(kind, path).inc(n)


def _semantic_match(entry: CacheEntry, similarity: float, ext: Extracted, threshold: float) -> bool:
    """의미 캐시 적중 조건: 유사도 ≥ 기준 그리고 슬롯 종류 같음(계약 ⑤) + 오적중 막기 2가지.

    - "오늘·내일" 같은 상대 날짜 말이 같다 (오늘 질문의 :today SQL을 내일 질문에 쓰지 않게)
    - SQL에 굳어 있는 말(리클라이너·15세 같은 값)이 새 질문에도 있다
    - 뜻을 뒤집는 말 묶음(이후/이전·많은/적은·첫/마지막·제외)이 같다
      — 임베딩은 "20시 이후"와 "20시 전에"를 0.93으로 본다(11/10 실측)
    """
    return (
        similarity >= threshold
        and entry.kinds == ext.kinds
        and set(entry.relative_words) == set(ext.relative_words)
        and tpl.anchors_present(entry.anchors, ext.question)
        and tuple(entry.meaning) == meaning_signature(ext.question)
    )


def _subject_template(subject: str, ext: Extracted) -> str:
    """답 문장 앞부분의 슬롯 값을 빈칸으로("오늘 20시 이후 회차" → "오늘 {time} 이후 회차")."""
    text = subject
    for kind, slot in ext.slots.items():
        for form in sorted({str(slot.value), slot.raw}, key=len, reverse=True):
            if form:
                text = text.replace(form, "{" + kind + "}")
    # 다른 모양으로 적힌 값("오후 8시")이 남았을 수 있으면 일반 문장으로
    if {"time", "date", "n"} & set(ext.slots) and re.search(r"\d", text):
        return "조회 결과"
    return text


def _fill_subject(subject: str, ext: Extracted) -> str:
    for kind, slot in ext.slots.items():
        subject = subject.replace("{" + kind + "}", slot.raw)
    return subject


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
