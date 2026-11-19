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
검증 거부·SQL 오류·프리셋 빈칸 오류·조건 불일치가 나면 사유를 붙여 다시 묻는다. 남은 호출이 없으면
REJECTED(조건 불일치였으면 CLARIFY — 사용자에게 조건을 확인한다).

의미 검사(sql/conditions.py): Claude가 고른 SQL(프리셋 포함)이 질문에서 뽑은 날짜·극장·영화를
조건으로 갖는지 실행 전에 본다. 빠졌거나 다르면 실행하지 않고 다시 쓰게 한다.
읽지 못한 조건(OR·범위)은 통과.

잔여석(질문 시점 갱신): SQL이 잔여석을 읽으면 그 SQL이 조회하는 날짜 × 극장만
실행 직전에 공개 API에서 새로 받는다(snapshot/fresh.py).
잔여석 질문인데 날짜가 없으면 실행하지 않고 날짜를 되묻는다(CLARIFY).
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
from dataclasses import dataclass, replace
from datetime import date, datetime, timedelta
from typing import Any

import sqlglot
from sqlglot import exp
from sqlglot.errors import SqlglotError

from seatsac_ai import bookings as my_bookings
from seatsac_ai.cache import template as tpl
from seatsac_ai.cache.slots import (
    RELATIVE_DAYS,
    Extracted,
    extra_words,
    extract,
    is_followup,
    meaning_signature,
)
from seatsac_ai.cache.store import CacheEntry, ChatCache
from seatsac_ai.common.errors import ApiError, llm_unavailable, snapshot_not_ready
from seatsac_ai.common.metrics import AI_CACHE_REQUESTS, AI_CHAT, AI_TOKENS
from seatsac_ai.config import Settings
from seatsac_ai.llm import prompt
from seatsac_ai.llm.client import NO_TOOL, LlmClient, LlmUnavailable, ToolCall, Usage
from seatsac_ai.llm.tools import ASK_USER, OUT_OF_SCOPE, RUN_PRESET, RUN_SQL, tool_definitions
from seatsac_ai.resources import Preset, presets
from seatsac_ai.snapshot.fresh import Fresh, SeatRefresher
from seatsac_ai.snapshot.mapper import KST
from seatsac_ai.snapshot.store import SnapshotNotReady, SnapshotStore
from seatsac_ai.sql.conditions import Conditions, analyze
from seatsac_ai.sql.executor import (
    SQL_ERROR,
    TIMEOUT,
    QueryResult,
    SqlExecutionError,
    execute_async,
)
from seatsac_ai.sql.validator import (
    SqlRejected,
    ValidatedSql,
    exclude_started,
    sql_template,
    validate,
    with_screening_id,
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
CLARIFY_DATE_ANSWER = "언제 보실 건가요? 날짜를 골라 주세요."
CONDITION_MISMATCH = "CONDITION_MISMATCH"
SEAT_UNKNOWN_NOTE = (
    " 일부 회차는 잔여석을 확인하지 못했습니다(확인 불가). 잠시 후 다시 물어봐 주세요."
)
SEAT_STALE_NOTE = " 잔여석을 지금 새로 확인하지 못해 마지막으로 받은 값입니다."
SEAT_PARTIAL_NOTE = " 오늘이 아닌 날짜의 잔여석은 최대 10분 전 값일 수 있습니다."
CLARIFY_CONFIRM_HINT = "날짜·극장·영화를 한 문장에 하나씩만 넣어 다시 물어봐 주세요."
_REFRESH_MAX_DAYS = 2  # 질문 시점 갱신은 날짜 2개까지(× 극장 4 = catalog 최대 8번)
_CHOICE_MESSAGE_MAX = 300  # 계약 Choice.message maxLength
_CHOICES_MAX = 7  # 계약 ChatResponse.choices maxItems
_RELATIVE_WORDS = ("오늘", "내일", "모레")
_WEEKDAYS = "월화수목금토일"
_CLARIFY_REPLY_MAX = 15  # 되묻기 뒤 이 길이 이하의 답("내일")은 원래 질문에 붙여 다시 처리한다

_SLOT_FORMAT = {
    "time": re.compile(r"^([01]\d|2[0-3]):[0-5]\d$"),
    "date": re.compile(r"^\d{4}-\d{2}-\d{2}$"),
    "grade": re.compile(r"^(ALL|12|15|19)$"),
}


class _Retry(Exception):
    """Claude에게 사유를 붙여 다시 묻는다. rejection 은 남은 호출이 없을 때 쓸 거부 사유."""

    def __init__(self, message: str, rejection: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.rejection = rejection


class _Clarify(Exception):
    """실행하지 않고 사용자에게 되묻는다(body = CLARIFY 응답)."""

    def __init__(self, body: dict[str, Any]) -> None:
        super().__init__(body["answer"])
        self.body = body


@dataclass(frozen=True)
class _Ask:
    """의미 검사·되묻기에 쓰는 이번 질문. ext = 질문에서 뽑은 날짜·극장·영화."""

    question: str
    ext: Extracted
    today: date

    def days(self) -> list[str]:
        """질문이 말한 날짜(YYYY-MM-DD). 없으면 빈 목록."""
        days = {(self.today + timedelta(days=RELATIVE_DAYS[w])).isoformat()
                for w in self.ext.relative_words}  # fmt: skip
        if "date" in self.ext.slots:
            days.add(str(self.ext.slots["date"].value))
        return sorted(days)


@dataclass(frozen=True)
class _Ran:
    """실행 결과 + 잔여석 정보.

    seats = 잔여석을 읽은 SQL이면 그 조건, fresh = 질문 시점 갱신 결과.
    partial = 갱신 범위를 줄였다(날짜를 읽지 못했거나 너무 많음).
    """

    sql: str
    result: QueryResult
    seats: Conditions | None = None
    fresh: Fresh | None = None
    partial: bool = False  # 조회 범위 일부만 새로 받았다(나머지는 주기 갱신 값)


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
        refresher: SeatRefresher | None = None,
    ) -> None:
        self._store = store
        self._refresher = refresher  # 잔여석을 읽는 SQL 직전에 회차를 새로 받는다
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
        if previous is not None and previous.get("path") == "CLARIFY":
            # 되묻기에 짧게 답했으면("내일") 원래 질문에 붙인다. 버튼은 완성된 질문을 보내므로
            # 원래 질문이 이미 들어 있으면 붙이지 않는다(짧은 질문의 버튼이 두 번 붙었다, #140 리뷰)
            if (
                len(question.strip()) <= _CLARIFY_REPLY_MAX
                and not is_followup(question)
                and previous["question"] not in question
            ):
                question = f"{question.strip()} {previous['question']}"
            previous = None
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
                ask = _Ask(question, ext or self._extract(question, now), now.date())
                body, made, calls = await self._ask_claude(
                    ask, now, system_params, deadline, usage, previous if follow_up else None
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
        self, ask: _Ask, now, system_params, deadline, usage, previous
    ) -> tuple[dict[str, Any], _Made | None, int]:
        calls = 0
        system = prompt.system_blocks(self._s.prompt_cache_enabled)
        messages: list[dict[str, Any]] = [
            {
                "role": "user",
                "content": prompt.user_text(ask.question, now, previous, self._data_range()),
            }
        ]
        last_rejection: dict[str, Any] | None = None
        last_sql: str | None = None
        # Claude 실패 지표의 path: 직전에 고른 도구를 따른다.
        # 첫 호출(도구를 고르기 전) 실패는 nl2sql로 센다 — 경로를 정하기 전이라 나눌 수 없다.
        failed_path = "nl2sql"
        nudged = False
        try:
            while calls < self._s.llm_max_calls_per_query:
                timeout = min(self._s.llm_timeout_seconds, deadline - time.monotonic())
                if timeout <= 0:
                    raise LlmUnavailable("TIMEOUT")
                call = await self._llm.call(system, self._tools, messages, timeout)
                calls += 1
                usage.add(call.usage)
                if call.name == NO_TOOL:
                    # tool_choice=auto: 도구 없이 쓴 글은 표를 조회한 답이 아니라서 버린다.
                    # 한 번만 다시 시키고, 또 그러면 범위 밖 문구로 답한다
                    log.warning("Claude가 도구를 호출하지 않았다(%d번째 호출)", calls)
                    if not nudged and calls < self._s.llm_max_calls_per_query:
                        nudged = True
                        messages += _nudge(call)
                        continue
                    return _empty("OUT_OF_SCOPE", OUT_OF_SCOPE_ANSWER, self._as_of()), None, calls
                failed_path = "preset" if call.name == RUN_PRESET else "nl2sql"
                try:
                    body, made = await self._run_tool(call, ask, system_params, deadline)
                    return body, made, calls
                except _Clarify as clarify:
                    return clarify.body, None, calls
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
        if last.get("reason") == CONDITION_MISMATCH:
            # 틀린 조건으로 답하지 않는다 — 사용자에게 조건을 확인하고 다시 찾는다
            return self._clarify_confirm(ask), None, calls
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
            "screeningIds": None,
            "rowCount": len(rows),
            "truncated": page.truncated,
            "sql": None,
            "presetId": None,
            "asOf": as_of,
            "rejection": None,
            "choices": None,
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
        if entry is not None and (
            entry.kinds != ext.kinds or tuple(entry.meaning) != meaning_signature(ext.template)
        ):
            # 키는 같은데 슬롯 종류나 뜻 묶음(이후/이전·많은/적은 …)이 다르면 쓰지 않는다
            # (hit로 세지 않음)
            entry = None
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
            ran = await self._execute(
                entry.sql, params, deadline, claude_sql=entry.origin == "NL2SQL"
            )
        except _Retry as retry:  # 스냅샷 모양이 바뀌었거나 너무 오래 걸림 — Claude로
            log.warning("캐시 SQL 실행 실패(Claude로 처리): %s", retry.message)
            return None, vector
        subject = _fill_subject(entry.subject, ext)
        shown_sql = tpl.render(ran.sql, params)
        answer = _answer(subject, ran.result) + self._date_note(shown_sql)
        body = self._result_body(path, answer, ran, shown_sql, entry.preset_id)
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
            again = await self._execute(
                template.sql,
                system_params | ext.values(),
                deadline,
                claude_sql=made.origin == "NL2SQL",
                refresh=False,  # 방금 실행한 결과와 같은 데이터로 비교해야 한다
            )
        except (_Retry, LlmUnavailable) as exc:
            log.info("캐시하지 않음(템플릿 실행 실패): %s", exc)
            return
        if again.result.rows != made.result.rows:
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
            # 뜻 묶음은 키와 같은 글자(다듬은 템플릿)로 낸다. 원문으로 내면 "20시 전"과
            # "20시 전에"가 키는 같은데 뜻 묶음이 달라 서로 못 쓰고 덮어쓴다(#143 리뷰)
            meaning=meaning_signature(ext.template),
        )
        if vector is None:
            vector = await self._embed(cache, ext.template)
        await cache.put(ext.key_text, entry, vector)

    # --- 도구별 처리 ---------------------------------------------------------

    async def _run_tool(
        self, call: ToolCall, ask: _Ask, system_params, deadline
    ) -> tuple[dict[str, Any], _Made | None]:
        if call.name == OUT_OF_SCOPE:
            return _empty("OUT_OF_SCOPE", OUT_OF_SCOPE_ANSWER, self._as_of()), None
        if call.name == ASK_USER:
            # Claude가 「잔여석을 묻는데 날짜를 알 수 없다」고 판단했다
            return self._clarify_date(ask), None
        if call.name == RUN_PRESET:
            preset, slots = self._resolve_preset(call.input)
            ran = await self._execute(preset.sql, system_params | slots, deadline, ask=ask)
            result = ran.result
            subject = _preset_subject(preset.title, slots)
            answer = _answer(subject, result)
            body = self._result_body("PRESET", answer, ran, None, preset.preset_id)
            # 캐시에는 제목의 빈칸 모양으로 남긴다. 실제 시각("20시")으로 남기면 "오후 8시"처럼
            # 다르게 말한 질문에서 값이 빈칸으로 안 바뀌어 적중 답이 「조회 결과」가 된다(#111 리뷰)
            # 전제: 제목의 빈칸 표기는 "○시" 하나뿐 — tests/test_presets.py 가 지킨다
            cached_subject = preset.title.replace("○시", "{time}")
            return body, _Made("PRESET", preset.sql, preset, slots, cached_subject, result)
        if call.name == RUN_SQL:
            sql = str(call.input.get("sql", ""))
            # maxLength 80은 도구 스키마의 힌트일 뿐 API가 강제하지 않는다
            explanation = str(call.input.get("explanation") or "조회 결과").strip()[:80]
            ran = await self._execute(sql, system_params, deadline, claude_sql=True, ask=ask)
            missing = self._missing_names(ran.sql) if ran.result.row_count == 0 else None
            if missing:
                # "결과 없음"만으로는 지점이 없는 건지 회차가 없는 건지 알 수 없다
                # (11/18 실사용 점검).
                # 없는 이름에 대한 답은 캐시하지 않는다 — 편성이 바뀌면 답도 바뀐다
                return self._result_body("NL2SQL", missing, ran, ran.sql, None), None
            answer = _answer(explanation, ran.result) + self._date_note(ran.sql)
            body = self._result_body("NL2SQL", answer, ran, ran.sql, None)
            return body, _Made("NL2SQL", sql, None, {}, explanation, ran.result)
        raise _Retry(f"알 수 없는 도구 {call.name}")

    async def _execute(
        self,
        sql: str,
        params: dict[str, Any],
        deadline: float,
        claude_sql: bool = False,
        refresh: bool = True,
        ask: _Ask | None = None,
    ) -> _Ran:
        """검증 → 조건 분석 → (Claude 경로) 의미 검사·되묻기 → (잔여석) 질문 시점 갱신 → 실행.

        프리셋·캐시·Claude SQL 모두 여기를 지난다. 의미 검사는 Claude가 고른 SQL에만 한다(ask).
        캐시 템플릿은 저장할 때 같은 결과가 나오는지 확인했고, 저장 전 검증 재실행(refresh=False)은
        방금 결과와 같은 데이터로 비교해야 해서 갱신하지 않는다.

        Claude가 쓴 SQL(claude_sql)은 screening 을 읽는 SELECT마다 `starts_at > :now` 를
        붙여 실행한다(data-model 6-1, #93 리뷰). 조건을 빼먹거나 지금보다 이른 시각과 비교해도
        시작한 회차가 나오지 않는다. 프리셋은 SQL에 :now 가 이미 들어 있다.

        행 하나가 회차 하나인 SQL은 회차 ID 칸을 덧붙여 실행하고 결과에서 떼어 둔다
        (result.screening_ids → 계약 screeningIds). 프리셋·Claude·캐시 템플릿이 모두 여기를 지나므로
        어느 경로든 같다. 돌려주는 SQL(응답 sql·세션·로그)은 덧붙이기 전 그대로다.
        """
        try:
            validated = validate(sql, self._s.max_rows)
        except SqlRejected as r:
            rejection = {"reason": r.reason, "object": r.object}
            raise _Retry(f"SQL이 거부되었다({r.reason}): {r.message}", rejection) from r
        if claude_sql:
            validated = exclude_started(validated)
        conditions = analyze(validated, params)
        if ask is not None:
            self._check(conditions, ask)
        fresh: Fresh | None = None
        partial = False
        if refresh and self._refresher is not None and conditions.reads_seats:
            days, theater_ids, partial = self._refresh_range(conditions, params)
            # 갱신 대기도 요청 전체 기한 안에서만(실행 계약 9-1 시간 예산, #140 리뷰)
            budget = min(self._s.snapshot_refresh_timeout_seconds, deadline - time.monotonic())
            if budget > 0:
                fresh = await self._refresher.ensure_fresh(days, theater_ids, timeout=budget)
            else:
                fresh = Fresh(ok=False)  # 남은 시간이 없으면 기존 스냅샷으로 답한다
        timeout = min(self._s.sql_timeout_seconds, deadline - time.monotonic())
        if timeout <= 0:
            raise LlmUnavailable("TIMEOUT")
        try:
            result = await self._run(validated, params, timeout)
        except SqlExecutionError as e:
            if e.reason == TIMEOUT:
                # 다시 물어도 같은 무거운 SQL일 가능성이 커서 바로 거부한다
                raise _Retry(e.message, {"reason": TIMEOUT, "final": True}) from e
            raise _Retry(f"SQL 실행 오류: {e.message}", {"reason": SQL_ERROR}) from e
        seats = conditions if conditions.reads_seats else None
        return _Ran(validated.sql, _hide_seat_source(result), seats, fresh, partial)

    # --- 의미 검사 · 되묻기 · 잔여석 범위 ----------------------------------------

    def _check(self, conditions: Conditions, ask: _Ask) -> None:
        """질문의 날짜·극장·영화가 SQL 조건에 있는지.

        빠졌거나 다르면 _Retry, 날짜를 물어야 하면 _Clarify.

        읽지 못한 조건(OR·범위 비교)은 판단을 보류하고 통과시킨다 — 확실히 어긋난 것만 막는다.
        """
        days = ask.days()
        if conditions.reads_seats and not days and "date" not in conditions.referenced:
            # 잔여석은 날짜가 정해져야 그 날짜만 새로 받을 수 있다(오늘로 짐작하지 않는다)
            raise _Clarify(self._clarify_date(ask))
        problems: list[str] = []
        for day in days:
            if conditions.has_date(day) is False:
                problems.append(
                    f"질문의 날짜는 {day}인데 SQL에 business_date = '{day}' 조건이 없다"
                )
        theater = ask.ext.slots.get("theater")
        if theater is not None:
            name = str(theater.value)
            if conditions.has_theater(name, self._theater_id(name)) is False:
                problems.append(f"질문의 극장은 '{name}'인데 SQL에 그 극장 조건이 없다")
        movie = ask.ext.slots.get("movie")
        if movie is not None and conditions.has_movie(str(movie.value)) is False:
            problems.append(f"질문의 영화는 '{movie.value}'인데 SQL에 그 영화 조건이 없다")
        if problems:
            message = "; ".join(problems)
            raise _Retry(
                f"조건이 질문과 다르다: {message}",
                {"reason": CONDITION_MISMATCH, "message": "질문의 조건과 다른 조회입니다."},
            )

    def _refresh_range(
        self, conditions: Conditions, params: dict[str, Any]
    ) -> tuple[list[date], list[int] | None, bool]:
        """잔여석을 새로 받을 날짜 × 극장, 그리고 범위를 줄였는지(partial).

        날짜는 항상 정한다 — 읽지 못했으면(범위 비교·OR) 오늘만, 많으면 앞의 2개만 새로 받고
        나머지는 주기 갱신 값으로 답하며 답 문장에 알린다. 7일 × 극장 전부(28번)를 다시 받지
        않는다(#140 리뷰). 극장 조건을 읽지 못하면 None(극장 전부).
        """
        days: list[date] | None = None
        if conditions.dates is not None:
            try:
                days = sorted(date.fromisoformat(d) for d in conditions.dates)
            except ValueError:
                days = None
        partial = False
        if not days:
            days, partial = [date.fromisoformat(params["today"])], True
        elif len(days) > _REFRESH_MAX_DAYS:
            days, partial = days[:_REFRESH_MAX_DAYS], True
        ids: set[int] = set(conditions.theater_ids or ())
        if conditions.theaters is not None:
            with self._store.reader() as conn:
                theaters = list(conn.execute("SELECT theater_id, name FROM theater"))
            for theater_id, name in theaters:
                if any(m.accepts(name) for m in conditions.theaters):
                    ids.add(theater_id)
        return days, (sorted(ids) or None), partial

    def _theater_id(self, name: str) -> int | None:
        with self._store.reader() as conn:
            row = conn.execute("SELECT theater_id FROM theater WHERE name = ?", (name,)).fetchone()
        return row[0] if row else None

    def _clarify_date(self, ask: _Ask) -> dict[str, Any]:
        """날짜를 고르는 버튼(최대 7개). 누르면 「날짜 말 + 원래 질문」을 그대로 다시 보낸다."""
        choices = []
        for offset in range(min(self._s.snapshot_days, _CHOICES_MAX)):
            day = ask.today + timedelta(days=offset)
            short = f"{day.month}/{day.day}({_WEEKDAYS[day.weekday()]})"
            if offset < len(_RELATIVE_WORDS):
                word = _RELATIVE_WORDS[offset]
                label = f"{word} {short}"
            else:
                word = f"{day.month}월 {day.day}일"
                label = short
            message = f"{word} {ask.question}"
            if len(message) > _CHOICE_MESSAGE_MAX:
                # 300자를 넘으면 날짜 말만 보낸다 — 서버가 원래 질문에 붙여 처리한다
                message = word
            choices.append({"label": label, "message": message})
        return _clarify(CLARIFY_DATE_ANSWER, choices, self._as_of())

    def _clarify_confirm(self, ask: _Ask) -> dict[str, Any]:
        """조건을 반영한 SQL을 끝내 못 만들었다 — 이해한 조건을 보여 주고 다시 물어 달라고 한다.

        버튼은 주지 않는다. 같은 질문을 다시 보내면 같은 이유로 또 걸려 Claude 3회만 더 쓴다
        (#140 리뷰).
        """
        parts = []
        for day in ask.days():
            d = date.fromisoformat(day)
            parts.append(f"{d.month}/{d.day}({_WEEKDAYS[d.weekday()]})")
        for kind in ("theater", "movie"):
            if kind in ask.ext.slots:
                parts.append(str(ask.ext.slots[kind].value))
        summary = " · ".join(parts) or "질문하신 조건"
        answer = f"{summary} 조건을 정확히 반영한 조회를 만들지 못했어요. {CLARIFY_CONFIRM_HINT}"
        return _clarify(answer, [], self._as_of())

    async def _run(
        self, validated: ValidatedSql, params: dict[str, Any], timeout: float
    ) -> QueryResult:
        async def run(query: ValidatedSql, seconds: float) -> QueryResult:
            return await execute_async(
                self._store, query, params, timeout_seconds=seconds, max_rows=self._s.max_rows
            )

        with_id = with_screening_id(validated)
        if with_id is None:
            return await run(validated, timeout)
        started = time.monotonic()
        try:
            return _take_screening_ids(await run(with_id, timeout))
        except SqlExecutionError as first:
            if first.reason == TIMEOUT:
                raise
            # 칸을 덧붙인 탓인지 원래 SQL이 틀린 건지 아직 모른다 — 원래 SQL로 다시 실행한다.
            # 원래 SQL도 실패하면 그 오류가 그대로 올라가 기존 처리(Claude 재요청)를 탄다
            left = timeout - (time.monotonic() - started)
            if left <= 0:
                raise LlmUnavailable("TIMEOUT") from first
            result = await run(validated, left)
            # 덧붙인 칸만 문제였다 — 답은 그대로 나가고 좌석 링크만 없다. SQL·값은 남기지 않는다
            log.warning(
                "회차 ID 칸을 덧붙인 SQL만 실패(원래 SQL로 답함): %s",
                type(first.__cause__).__name__,
            )
            return result

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
            if name == "grade":
                # tool_choice auto는 스키마를 강제하지 않아 12가 정수로 올 수 있다
                value = str(value)
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

    # --- 스냅샷에 없는 것 알리기 ---------------------------------------------

    def _data_range(self) -> tuple[str, str] | None:
        with self._store.reader() as conn:
            first, last = conn.execute(
                "SELECT min(business_date), max(business_date) FROM screening"
            ).fetchone()
        return (first, last) if first and last else None

    def _date_note(self, sql: str) -> str:
        """SQL이 편성이 있는 마지막 날 뒤의 날짜까지 물었으면 그 사실을 답에 붙인다.

        시드가 며칠치뿐이면 "이번 주말(토·일)"이 토요일 하루치인데도 그렇게 보이지 않는다.
        """
        data = self._data_range()
        asked = _business_dates(sql)
        if not data or not asked or max(asked) <= data[1]:
            return ""
        last = datetime.fromisoformat(data[1])
        return f" 회차 정보는 {last.month}월 {last.day}일까지만 있어 그 뒤 날짜는 빠져 있습니다."

    def _missing_names(self, sql: str) -> str | None:
        """결과가 0건일 때: SQL이 찾은 극장·영화 이름이 스냅샷에 아예 없으면 그렇게 말해 준다."""
        try:
            tree = sqlglot.parse_one(sql, read="sqlite")
        except SqlglotError:
            return None
        asked: dict[str, list[str]] = {"theater": [], "movie": []}
        for node in tree.find_all(exp.EQ, exp.Like, exp.In):
            column = node.this
            kind = _NAME_COLUMNS.get(column.name) if isinstance(column, exp.Column) else None
            if kind is None:
                continue
            values = node.expressions if isinstance(node, exp.In) else [node.expression]
            for value in values:
                if isinstance(value, exp.Literal) and value.is_string:
                    text = value.this.strip("% ").strip()
                    if text and text not in asked[kind]:
                        asked[kind].append(text)
        if not any(asked.values()):
            return None
        with self._store.reader() as conn:
            theaters = conn.execute("SELECT name, region FROM theater ORDER BY name").fetchall()
            movies = conn.execute("SELECT title, title_en FROM movie").fetchall()
        names = [str(row[0]) for row in theaters]
        regions = {str(row[1]) for row in theaters if row[1]}
        known_movie = [_squash(str(v)) for row in movies for v in row if v]
        # 지역은 그 글자 그대로일 때만 안다("서울숲"을 "서울"로 보지 않게).
        # 극장 이름은 앞뒤 일부도 인정
        gone_theater = [
            a
            for a in asked["theater"]
            if a not in regions and not any(a in n or n in a for n in names)
        ]
        gone_movie = [a for a in asked["movie"] if not any(_squash(a) in k for k in known_movie)]
        if any(_HANGUL.search(a) for a in gone_movie):
            # Claude가 영어 제목까지 같이 찾았을 때("Zootopia") 사용자가 쓴 한글 제목만 보여 준다
            gone_movie = [a for a in gone_movie if _HANGUL.search(a)]
        parts = []
        if gone_theater:
            listed = "·".join(names)
            parts.append(
                f"'{', '.join(gone_theater)}' 지점은 없습니다. 예매할 수 있는 지점: {listed}."
            )
        if gone_movie:
            parts.append(f"'{', '.join(gone_movie)}'은(는) 지금 상영 정보에 없는 영화입니다.")
        return " ".join(parts) or None

    # --- 응답·기록 -----------------------------------------------------------

    def _as_of(self) -> str:
        as_of = self._store.as_of() or datetime.now(KST)
        return as_of.astimezone(KST).isoformat(timespec="seconds")

    def _result_body(self, path, answer, ran: _Ran, sql, preset_id) -> dict[str, Any]:
        result = ran.result
        fresh = ran.fresh
        if fresh is not None and fresh.ok and fresh.at is not None:
            # 잔여석을 방금 새로 받았다 — 기준 시각은 그 시각(주기 갱신 시각이 아니라)
            as_of = fresh.at.astimezone(KST).isoformat(timespec="seconds")
        else:
            as_of = self._as_of()
        return {
            "answer": f"{answer} ({_clock(as_of)} 기준){_seat_note(ran)}",
            "path": path,
            "columns": result.columns,
            "rows": result.rows,
            "screeningIds": result.screening_ids,
            "rowCount": result.row_count,
            "truncated": result.truncated,
            "sql": sql,
            "presetId": preset_id,
            "asOf": as_of,
            "rejection": None,
            "choices": None,
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


_NUDGE = "글로 답하지 말고 run_preset·run_sql·out_of_scope 중 하나를 반드시 호출하라."


def _nudge(call: ToolCall) -> list[dict[str, Any]]:
    """도구 없이 글로 답했을 때 되돌려 보내는 말(tool_use 가 없어 tool_result 대신 평문)."""
    turns: list[dict[str, Any]] = []
    if any(block.get("type") == "text" for block in call.content):
        turns.append({"role": "assistant", "content": call.content})
    turns.append({"role": "user", "content": _NUDGE})
    return turns


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


# 극장·영화를 이름으로 거르는 칸 → 종류
_NAME_COLUMNS = {"name": "theater", "region": "theater", "title": "movie", "title_en": "movie"}
_DATE_LITERAL = re.compile(r"'(\d{4}-\d{2}-\d{2})")
_HANGUL = re.compile(r"[가-힣]")


def _hide_seat_source(result: QueryResult) -> QueryResult:
    """결과 표에서 remaining_seats_source 칸을 뺀다(사용자에게는 뜻 없는 내부 값).

    Claude가 이 칸을 SELECT에 넣을 때도 있고 안 넣을 때도 있어 답마다 표 모양이 달랐다.
    빼기 전에 그 값을 쓴다: BOOKING 이 아닌 줄의 잔여석은 확인된 값이 아니므로 비운다(화면 '–').
    """
    names = [c["name"] for c in result.columns]
    if "remaining_seats_source" not in names:
        return result
    src = names.index("remaining_seats_source")
    seat = names.index("remaining_seats") if "remaining_seats" in names else None
    rows = []
    for row in result.rows:
        row = list(row)
        if seat is not None and row[src] != "BOOKING":
            row[seat] = None
        del row[src]
        rows.append(row)
    columns = [c for i, c in enumerate(result.columns) if i != src]
    return replace(result, columns=columns, rows=rows)


def _take_screening_ids(result: QueryResult) -> QueryResult:
    """서버가 덧붙인 회차 ID 칸(validator.with_screening_id)을 결과 표에서 떼어 따로 둔다.

    덧붙인 칸은 항상 마지막이다 — 이름으로 찾지 않는다(같은 이름의 칸이 앞에 있어도 헷갈리지 않게).
    """
    ids = [row[-1] for row in result.rows]
    rows = [row[:-1] for row in result.rows]
    known = any(i is not None for i in ids)
    return replace(
        result, columns=result.columns[:-1], rows=rows, screening_ids=ids if known else None
    )


def _business_dates(sql: str) -> list[str]:
    """SQL이 business_date 와 비교한 날짜 값만 (starts_at 의 다음 날 경계 같은 값은 빼고)."""
    try:
        tree = sqlglot.parse_one(sql, read="sqlite")
    except SqlglotError:
        return []
    dates: list[str] = []
    for column in tree.find_all(exp.Column):
        if column.name != "business_date":
            continue
        parent = column.parent
        exclusive_end = False
        if isinstance(parent, exp.In):
            values = parent.expressions
        elif isinstance(parent, exp.Between):
            # BETWEEN 은 Binary 가 아니다 — "이번 주말"을 BETWEEN 으로 써도 알림이 붙게(#143 리뷰)
            values = [parent.args["low"], parent.args["high"]]
        elif isinstance(parent, exp.Binary):
            on_left = parent.left is column
            values = [parent.right if on_left else parent.left]
            # business_date < '다음 날'(끝 제외)은 그 날짜를 묻지 않은 것이다 → 하루 앞으로 본다
            exclusive_end = isinstance(parent, exp.LT if on_left else exp.GT)
        else:
            continue
        for value in values:
            if isinstance(value, exp.Literal) and value.is_string:
                found = _DATE_LITERAL.findall("'" + value.this)
                if exclusive_end:
                    found = [(date.fromisoformat(d) - timedelta(days=1)).isoformat() for d in found]
                dates += found
    return dates


def _squash(text: str) -> str:
    return re.sub(r"\s+", "", text).lower()


def _semantic_match(entry: CacheEntry, similarity: float, ext: Extracted, threshold: float) -> bool:
    """의미 캐시 적중 조건: 유사도 ≥ 기준 그리고 슬롯 종류 같음(계약 ⑤) + 오적중 막기 2가지.

    - "오늘·내일" 같은 상대 날짜 말이 같다 (오늘 질문의 :today SQL을 내일 질문에 쓰지 않게)
    - SQL에 굳어 있는 말(리클라이너·15세 같은 값)이 새 질문에도 있다
    - 뜻을 뒤집는 말 묶음(이후/이전·많은/적은·첫/마지막·제외)이 같다
      — 임베딩은 "20시 이후"와 "20시 전에"를 0.93으로 본다(11/10 실측)
    - 새 질문에만 있는 낱말이 없다 — 없는 지점("강남")·새 조건("리클라이너관")이 붙은 질문에
      앞 질문의 SQL을 쓰지 않게(slots.extra_words, 11/18 실사용 점검)
    """
    return (
        similarity >= threshold
        and entry.kinds == ext.kinds
        and set(entry.relative_words) == set(ext.relative_words)
        and tpl.anchors_present(entry.anchors, ext.question)
        and tuple(entry.meaning) == meaning_signature(ext.template)
        and not extra_words(ext.template, entry.template)
    )


def _preset_subject(title: str, slots: dict[str, Any]) -> str:
    """버튼 제목의 "○시"를 실제 시각으로("오늘 ○시 이후 회차" + 20:00 → "오늘 20시 이후 회차")."""
    time = str(slots.get("time") or "")
    if "○시" not in title or ":" not in time:
        return title
    hour, minute = time.split(":")[:2]
    clock = f"{int(hour)}시" + (f" {int(minute)}분" if int(minute) else "")
    return title.replace("○시", clock)


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


MOVIE_NAMES_IN_ANSWER = 3  # 답 문장에 이름을 적는 영화 수. 나머지는 "외 N편"


def _answer(subject: str, result: QueryResult) -> str:
    if result.row_count == 0:
        return f"{subject}: 조건에 맞는 결과가 없습니다."
    more = " (500건까지 표시)" if result.truncated else ""
    return f"{subject}: {result.row_count}건입니다.{more}" + _movie_names(result)


def _movie_names(result: QueryResult) -> str:
    """결과에 영화가 두 편 이상이면 이름을 문장에 넣고 어느 영화를 볼지 묻는다.

    "런타임 2시간 이상 영화: 3건입니다"만으로는 표를 읽어야 무슨 영화인지 안다. 화면은 같은 조건
    (movie_title 칸, 서로 다른 영화 2편 이상)일 때 영화 고르는 버튼을 띄운다(features/ai-chat).
    """
    names = [c["name"] for c in result.columns]
    if "movie_title" not in names:
        return ""
    col = names.index("movie_title")
    titles = list(dict.fromkeys(str(r[col]) for r in result.rows if r[col]))
    if len(titles) < 2:
        return ""
    shown = ", ".join(titles[:MOVIE_NAMES_IN_ANSWER])
    rest = len(titles) - MOVIE_NAMES_IN_ANSWER
    tail = f" 외 {rest}편" if rest > 0 else ""
    # 조사("이/가")에 기대지 않는 문장 — 받침 없는 제목 뒤에 "이"가 붙지 않게(#143 리뷰)
    return f" 영화: {shown}{tail}. 어떤 영화의 상영 정보를 안내해 드릴까요?"


def _clock(iso: str) -> str:
    t = datetime.fromisoformat(iso)
    return f"{t.hour}시 {t.minute}분"


def _empty(path: str, answer: str, as_of: str) -> dict[str, Any]:
    return {
        "answer": answer,
        "path": path,
        "columns": None,
        "rows": None,
        "screeningIds": None,
        "rowCount": 0,
        "truncated": False,
        "sql": None,
        "presetId": None,
        "asOf": as_of,
        "rejection": None,
        "choices": None,
    }


def _clarify(answer: str, choices: list[dict[str, str]], as_of: str) -> dict[str, Any]:
    """되묻기(CLARIFY). choices = 버튼 [{label, message}] — 누르면 message 를 그대로 보낸다."""
    body = _empty("CLARIFY", answer, as_of)
    body["choices"] = choices
    return body


def _seat_note(ran: _Ran) -> str:
    """잔여석 답에 붙이는 안내. 새로 못 받았거나 확인 불가(NULL) 회차가 섞였을 때만."""
    if ran.seats is None:
        return ""
    note = SEAT_STALE_NOTE if ran.fresh is not None and not ran.fresh.ok else ""
    if not note and ran.partial:
        note = SEAT_PARTIAL_NOTE
    result = ran.result
    seat_index = [i for i, c in enumerate(result.columns) if c["name"] in ran.seats.seat_outputs]
    unknown_shown = any(row[i] is None for row in result.rows for i in seat_index)
    # sum(remaining_seats) 같은 묶음 계산은 확인 불가 회차를 조용히 빼고 센다
    unknown_summed = ran.seats.seat_aggregate and (ran.fresh is None or ran.fresh.unknown > 0)
    if unknown_shown or unknown_summed:
        note += SEAT_UNKNOWN_NOTE
    return note


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
